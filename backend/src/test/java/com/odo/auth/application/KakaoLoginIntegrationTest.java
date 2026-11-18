package com.odo.auth.application;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.odo.auth.application.KakaoLoginService.LoginResult;
import com.odo.auth.presentation.LoginSuccessHandler;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestCookies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import static com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository.REDIRECT_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class KakaoLoginIntegrationTest {

	private static final String FRONTEND = "http://localhost:5173";

	@Autowired
	KakaoLoginService kakaoLoginService;

	@Autowired
	LoginSuccessHandler loginSuccessHandler;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
	}

	@Test
	@DisplayName("처음 오는 카카오 회원번호면 정리한 닉네임으로 계정과 로그인 수단이 만들어진다")
	void firstLoginCreatesAccount() {
		LoginResult result = kakaoLoginService.login("12345", "🐥뽀짝 지수");

		assertThat(result.newAccount()).isTrue();
		assertThat(accountName(result.accountId())).isEqualTo("뽀짝지수");
		assertThat(jdbc.queryForMap("SELECT account_id, provider, provider_user_id FROM social_login"))
			.containsEntry("account_id", result.accountId())
			.containsEntry("provider", "KAKAO")
			.containsEntry("provider_user_id", "12345");
	}

	@Test
	@DisplayName("닉네임이 없으면 이름이 ODO사용자인 계정이 만들어진다")
	void firstLoginWithoutNickname() {
		LoginResult result = kakaoLoginService.login("12345", null);

		assertThat(accountName(result.accountId())).isEqualTo("ODO사용자");
	}

	@Test
	@DisplayName("이미 있는 회원번호면 새 계정이 생기지 않고 이름도 바뀌지 않는다")
	void existingLogin() {
		LoginResult first = kakaoLoginService.login("12345", "박준하");

		LoginResult second = kakaoLoginService.login("12345", "다른닉네임");

		assertThat(second.newAccount()).isFalse();
		assertThat(second.accountId()).isEqualTo(first.accountId());
		assertThat(count("account")).isEqualTo(1);
		assertThat(count("social_login")).isEqualTo(1);
		assertThat(accountName(first.accountId())).isEqualTo("박준하");
	}

	@Test
	@DisplayName("같은 회원번호로 동시에 처음 로그인해도 계정은 하나만 생긴다")
	void concurrentFirstLogin() throws Exception {
		int threads = 4;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<LoginResult>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<LoginResult> task = () -> {
					start.await();
					return kakaoLoginService.login("12345", "박준하");
				};
				futures.add(executor.submit(task));
			}
			start.countDown();

			List<Long> accountIds = new ArrayList<>();
			for (Future<LoginResult> future : futures) {
				accountIds.add(future.get().accountId());
			}

			assertThat(accountIds).containsOnly(accountIds.getFirst());
			assertThat(count("account")).isEqualTo(1);
			assertThat(count("social_login")).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("새로 가입하면 이름 확인 화면으로 보내고, 돌아갈 주소를 next로 넘기고, 갱신 토큰 쿠키를 발급한다")
	void successHandlerNewAccount() throws Exception {
		MockHttpServletRequest request = callbackRequest("/invite/abc");
		MockHttpServletResponse response = new MockHttpServletResponse();

		loginSuccessHandler.onAuthenticationSuccess(request, response, kakaoAuthentication(12345L, "박준하"));

		assertThat(response.getRedirectedUrl()).startsWith(FRONTEND + "/welcome?");
		UriComponents redirect = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
		assertThat(URLDecoder.decode(redirect.getQueryParams().getFirst("next"), StandardCharsets.UTF_8))
			.isEqualTo("/invite/abc");
		assertRefreshTokenCookieIssued(response);
		assertThat(count("account")).isEqualTo(1);
		assertThat(count("refresh_token")).isEqualTo(1);
	}

	@Test
	@DisplayName("기존 회원이면 돌아갈 주소로 바로 보내고 갱신 토큰 쿠키를 발급한다")
	void successHandlerExistingAccount() throws Exception {
		kakaoLoginService.login("12345", "박준하");
		MockHttpServletRequest request = callbackRequest("/invite/abc");
		MockHttpServletResponse response = new MockHttpServletResponse();

		loginSuccessHandler.onAuthenticationSuccess(request, response, kakaoAuthentication(12345L, "박준하"));

		assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/invite/abc");
		assertRefreshTokenCookieIssued(response);
		assertThat(count("account")).isEqualTo(1);
	}

	@Test
	@DisplayName("돌아갈 주소가 없으면 기존 회원은 첫 화면으로 간다")
	void successHandlerWithoutRedirect() throws Exception {
		kakaoLoginService.login("12345", "박준하");
		MockHttpServletResponse response = new MockHttpServletResponse();

		loginSuccessHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				kakaoAuthentication(12345L, "박준하"));

		assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/");
	}

	@Test
	@DisplayName("닉네임 제공에 동의하지 않은 카카오 사용자도 ODO사용자로 가입된다")
	void successHandlerWithoutNicknameConsent() throws Exception {
		Map<String, Object> attributes = Map.of("id", 12345L, "kakao_account",
				Map.of("profile_nickname_needs_agreement", true));
		MockHttpServletResponse response = new MockHttpServletResponse();

		loginSuccessHandler.onAuthenticationSuccess(callbackRequest("/"), response, authentication(attributes));

		assertThat(jdbc.queryForObject("SELECT name FROM account", String.class)).isEqualTo("ODO사용자");
		assertRefreshTokenCookieIssued(response);
	}

	private void assertRefreshTokenCookieIssued(MockHttpServletResponse response) {
		String setCookie = TestCookies.requireSetCookieHeader(response, "ODO_RT");
		assertThat(TestCookies.value(setCookie)).isNotBlank();
		assertThat(setCookie).containsIgnoringCase("HttpOnly");
		assertThat(setCookie).containsIgnoringCase("Secure");
		assertThat(setCookie).containsIgnoringCase("SameSite=Strict");
		assertThat(setCookie).contains("Path=/api/auth");
	}

	private static MockHttpServletRequest callbackRequest(String redirect) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
		request.setAttribute(REDIRECT_ATTRIBUTE, redirect);
		return request;
	}

	private static OAuth2AuthenticationToken kakaoAuthentication(long kakaoId, String nickname) {
		return authentication(
				Map.of("id", kakaoId, "kakao_account", Map.of("profile", Map.of("nickname", nickname))));
	}

	private static OAuth2AuthenticationToken authentication(Map<String, Object> attributes) {
		DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"), attributes,
				"id");
		return new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao");
	}

	private String accountName(long accountId) {
		return jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, accountId);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
