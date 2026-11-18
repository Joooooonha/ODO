package com.odo.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.RefreshTokenService.RefreshResult;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class RefreshTokenIntegrationTest {

	private static final String COOKIE = "ODO_RT";

	private static final long THIRTY_DAYS_SECONDS = Duration.ofDays(30).toSeconds();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RefreshTokenService refreshTokenService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private long accountId;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		accountId = TestAccounts.create(jdbc, "박준하", clock.instant());
	}

	@Nested
	@DisplayName("갱신")
	class Refresh {

		@Test
		@DisplayName("갱신하면 30분짜리 접근 토큰과 새 갱신 토큰을 받고, 쓴 토큰에는 사용 시각이 기록된다")
		void rotates() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			MvcResult result = refresh(token).andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andReturn();

			assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.accessToken")).isNotBlank();
			String newToken = refreshCookieValue(result.getResponse());
			assertThat(newToken).isNotBlank().isNotEqualTo(token);
			assertThat(usedAt(token)).isEqualTo(clock.instant());
			assertThat(usedAt(newToken)).isNull();
			assertThat(expiresAt(newToken)).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
		}

		@Test
		@DisplayName("갱신해서 받은 접근 토큰으로 로그인이 필요한 API를 쓸 수 있다")
		void accessTokenWorks() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);
			String accessToken = accessTokenOf(refresh(token).andReturn());

			mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(accountId));
		}

		@Test
		@DisplayName("갱신 토큰 쿠키 속성은 HttpOnly, Secure, SameSite=Strict, Path=/api/auth, Max-Age=30일이다")
		void cookieAttributes() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			String setCookie = TestCookies.requireSetCookieHeader(refresh(token).andReturn().getResponse(), COOKIE);

			assertThat(setCookie).containsIgnoringCase("HttpOnly");
			assertThat(setCookie).containsIgnoringCase("Secure");
			assertThat(setCookie).containsIgnoringCase("SameSite=Strict");
			assertThat(setCookie).contains("Path=/api/auth");
			assertThat(setCookie).contains("Max-Age=" + THIRTY_DAYS_SECONDS);
		}

		@Test
		@DisplayName("29일째에 갱신하면 그때부터 다시 30일 동안 쓸 수 있다")
		void sliding() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			clock.advance(Duration.ofDays(29));
			String second = refreshCookieValue(refresh(token).andExpect(status().isOk()).andReturn().getResponse());

			clock.advance(Duration.ofDays(29));
			refresh(second).andExpect(status().isOk());
		}

		@Test
		@DisplayName("발급 뒤 30일이 되기 직전까지는 갱신할 수 있다")
		void beforeExpiry() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			clock.advance(Duration.ofDays(30).minusSeconds(1));

			refresh(token).andExpect(status().isOk());
		}

		@Test
		@DisplayName("발급 뒤 30일이 지난 갱신 토큰은 거부되고 쿠키가 지워진다")
		void expired() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			clock.advance(Duration.ofDays(30).plusSeconds(1));

			assertRefreshFailed(refresh(token).andReturn());
		}

		@Test
		@DisplayName("DB에 없는 갱신 토큰은 거부된다")
		void unknown() throws Exception {
			assertRefreshFailed(refresh("unknown-token-value").andReturn());
		}

		@Test
		@DisplayName("갱신 토큰 쿠키가 없으면 거부된다")
		void missingCookie() throws Exception {
			MvcResult result = mockMvc.perform(post("/api/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_FAILED"))
				.andReturn();

			assertThat(TestCookies.setCookieHeader(result.getResponse(), COOKIE)).isPresent()
				.get()
				.asString()
				.contains("Max-Age=0");
		}

	}

	@Nested
	@DisplayName("동시 갱신과 도난 탐지")
	class Reuse {

		@Test
		@DisplayName("쓴 지 10초 안에 같은 갱신 토큰이 다시 오면 접근 토큰만 주고 갱신 토큰 쿠키는 건드리지 않는다")
		void withinGracePeriod() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);
			String second = refreshCookieValue(refresh(token).andReturn().getResponse());

			clock.advance(Duration.ofSeconds(10));
			MvcResult result = refresh(token).andExpect(status().isOk()).andReturn();

			assertThat(accessTokenOf(result)).isNotBlank();
			assertThat(TestCookies.setCookieHeader(result.getResponse(), COOKIE)).isEmpty();
			assertThat(countTokens()).isEqualTo(2);
			refresh(second).andExpect(status().isOk());
		}

		@Test
		@DisplayName("쓴 지 10초가 지난 갱신 토큰이 다시 오면 거부하고 그 family를 모두 무효로 한다")
		void afterGracePeriod() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);
			String second = refreshCookieValue(refresh(token).andReturn().getResponse());

			clock.advance(Duration.ofSeconds(11));
			assertRefreshFailed(refresh(token).andReturn());

			assertRefreshFailed(refresh(second).andReturn());
			assertThat(countTokens()).isZero();
		}

		@Test
		@DisplayName("도난 탐지는 다른 기기(다른 family)의 갱신 토큰에 영향을 주지 않는다")
		void otherFamilyUnaffected() throws Exception {
			String phone = refreshTokenService.issueForNewLogin(accountId);
			String laptop = refreshTokenService.issueForNewLogin(accountId);
			refresh(phone);

			clock.advance(Duration.ofSeconds(11));
			assertRefreshFailed(refresh(phone).andReturn());

			refresh(laptop).andExpect(status().isOk());
		}

		@Test
		@DisplayName("같은 갱신 토큰으로 동시에 갱신해도 rotation은 한 번만 일어나고 모두 성공한다")
		void concurrentRefresh() throws Exception {
			for (int round = 0; round < 10; round++) {
				DatabaseCleaner.clean(jdbc);
				long id = TestAccounts.create(jdbc, "박준하", clock.instant());
				String token = refreshTokenService.issueForNewLogin(id);

				List<RefreshResult> results = refreshConcurrently(token, 4);

				assertThat(results).as("round %d", round).allSatisfy(r -> assertThat(r.accessToken()).isNotBlank());
				assertThat(results.stream().filter(r -> r.newRefreshToken() != null)).as("round %d", round).hasSize(1);
				assertThat(countTokens()).as("round %d", round).isEqualTo(2);
			}
		}

		private List<RefreshResult> refreshConcurrently(String token, int threads) throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			try {
				List<Future<RefreshResult>> futures = new ArrayList<>();
				for (int i = 0; i < threads; i++) {
					Callable<RefreshResult> task = () -> {
						start.await();
						return refreshTokenService.refresh(token);
					};
					futures.add(executor.submit(task));
				}
				start.countDown();
				List<RefreshResult> results = new ArrayList<>();
				for (Future<RefreshResult> future : futures) {
					results.add(future.get());
				}
				return results;
			}
			finally {
				executor.shutdownNow();
			}
		}

	}

	@Nested
	@DisplayName("로그아웃")
	class Logout {

		@Test
		@DisplayName("로그아웃하면 204이고 쿠키가 지워지며, 그 갱신 토큰으로 더는 갱신할 수 없다")
		void logout() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			MvcResult result = mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, token)))
				.andExpect(status().isNoContent())
				.andReturn();

			assertThat(TestCookies.requireSetCookieHeader(result.getResponse(), COOKIE)).contains("Max-Age=0");
			assertRefreshFailed(refresh(token).andReturn());
		}

		@Test
		@DisplayName("rotation된 뒤의 토큰으로 로그아웃해도 그 family 전체가 무효가 된다")
		void logoutAfterRotation() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);
			String second = refreshCookieValue(refresh(token).andReturn().getResponse());

			mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, second)))
				.andExpect(status().isNoContent());

			assertThat(countTokens()).isZero();
		}

		@Test
		@DisplayName("로그아웃은 다른 기기의 로그인에 영향을 주지 않는다")
		void otherFamilyUnaffected() throws Exception {
			String phone = refreshTokenService.issueForNewLogin(accountId);
			String laptop = refreshTokenService.issueForNewLogin(accountId);

			mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, phone)))
				.andExpect(status().isNoContent());

			refresh(laptop).andExpect(status().isOk());
		}

		@Test
		@DisplayName("쿠키가 없거나 DB에 없는 토큰이어도 204다")
		void idempotent() throws Exception {
			mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
			mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, "unknown-token-value")))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("접근 토큰이 없어도 로그아웃할 수 있다")
		void withoutAccessToken() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);
			clock.advance(Duration.ofHours(1));

			mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, token)))
				.andExpect(status().isNoContent());
		}

	}

	@Nested
	@DisplayName("저장")
	class Storage {

		@Test
		@DisplayName("DB에는 갱신 토큰의 원문이 아니라 SHA-256 해시만 저장된다")
		void hashOnly() throws Exception {
			String token = refreshTokenService.issueForNewLogin(accountId);

			String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_token", String.class);

			assertThat(stored).isNotEqualTo(token).isEqualTo(sha256Hex(token));
		}

		@Test
		@DisplayName("만료 행 정리는 만료된 행만 지운다")
		void deleteExpired() {
			String old = refreshTokenService.issueForNewLogin(accountId);
			clock.advance(Duration.ofDays(31));
			String fresh = refreshTokenService.issueForNewLogin(accountId);

			int deleted = refreshTokenService.deleteExpired();

			assertThat(deleted).isEqualTo(1);
			assertThat(countByHash(old)).isZero();
			assertThat(countByHash(fresh)).isOne();
		}

	}

	private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, token)));
	}

	private void assertRefreshFailed(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
			.isEqualTo("REFRESH_FAILED");
		assertThat(TestCookies.requireSetCookieHeader(result.getResponse(), COOKIE)).contains("Max-Age=0");
	}

	private static String refreshCookieValue(MockHttpServletResponse response) {
		return TestCookies.value(TestCookies.requireSetCookieHeader(response, COOKIE));
	}

	private static String accessTokenOf(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
	}

	private Instant usedAt(String token) {
		Timestamp usedAt = jdbc.queryForObject("SELECT used_at FROM refresh_token WHERE token_hash = ?",
				Timestamp.class, sha256Hex(token));
		return usedAt == null ? null : usedAt.toInstant();
	}

	private Instant expiresAt(String token) {
		return jdbc
			.queryForObject("SELECT expires_at FROM refresh_token WHERE token_hash = ?", Timestamp.class,
					sha256Hex(token))
			.toInstant();
	}

	private int countTokens() {
		return jdbc.queryForObject("SELECT count(*) FROM refresh_token", Integer.class);
	}

	private int countByHash(String token) {
		return jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class,
				sha256Hex(token));
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
