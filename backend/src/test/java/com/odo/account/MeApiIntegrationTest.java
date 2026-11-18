package com.odo.account;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.odo.auth.token.AccessTokenIssuer;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class MeApiIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

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
	@DisplayName("인증")
	class Authentication {

		@Test
		@DisplayName("접근 토큰이 없으면 401 UNAUTHENTICATED다")
		void noToken() throws Exception {
			mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}

		@Test
		@DisplayName("JWT 형식이 아닌 토큰은 401 UNAUTHENTICATED다")
		void malformedToken() throws Exception {
			getMe("not-a-jwt").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}

		@Test
		@DisplayName("서명을 바꾼 토큰은 401 UNAUTHENTICATED다")
		void tamperedSignature() throws Exception {
			String token = accessTokenIssuer.issue(accountId);
			int signatureMiddle = token.lastIndexOf('.') + (token.length() - token.lastIndexOf('.')) / 2;
			String tampered = token.substring(0, signatureMiddle)
					+ (token.charAt(signatureMiddle) == 'A' ? 'B' : 'A') + token.substring(signatureMiddle + 1);

			getMe(tampered).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}

		@Test
		@DisplayName("본문의 계정 ID를 바꾼 토큰은 401 UNAUTHENTICATED다")
		void tamperedPayload() throws Exception {
			long otherAccount = TestAccounts.create(jdbc, "다른사람", clock.instant());
			String token = accessTokenIssuer.issue(accountId);
			String[] parts = token.split("\\.");
			Map<String, Object> claims = claimsOf(token);
			claims.put("sub", String.valueOf(otherAccount));
			String forgedPayload = Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(JSON.writeValueAsBytes(claims));

			getMe(parts[0] + "." + forgedPayload + "." + parts[2]).andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("발급 뒤 30분이 되기 직전까지는 쓸 수 있다")
		void beforeExpiry() throws Exception {
			String token = accessTokenIssuer.issue(accountId);

			clock.advance(Duration.ofMinutes(30).minusSeconds(1));

			getMe(token).andExpect(status().isOk());
		}

		@Test
		@DisplayName("발급 뒤 30분이 지난 토큰은 401 UNAUTHENTICATED다")
		void expired() throws Exception {
			String token = accessTokenIssuer.issue(accountId);

			clock.advance(Duration.ofMinutes(30).plusSeconds(1));

			getMe(token).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}

		@Test
		@DisplayName("접근 토큰은 HS256으로 서명되고 본문에는 sub, iat, exp만 있으며 유효 시간은 30분이다")
		void tokenContents() throws Exception {
			String token = accessTokenIssuer.issue(accountId);

			Map<String, Object> header = decodePart(token.split("\\.")[0]);
			Map<String, Object> claims = claimsOf(token);

			assertThat(header).containsEntry("alg", "HS256");
			assertThat(claims).containsOnlyKeys("sub", "iat", "exp");
			assertThat(claims).containsEntry("sub", String.valueOf(accountId));
			long iat = ((Number) claims.get("iat")).longValue();
			long exp = ((Number) claims.get("exp")).longValue();
			assertThat(iat).isEqualTo(clock.instant().getEpochSecond());
			assertThat(exp - iat).isEqualTo(1800);
		}

	}

	@Nested
	@DisplayName("내 정보")
	class Me {

		@Test
		@DisplayName("내 ID와 이름을 돌려준다")
		void getMe() throws Exception {
			MeApiIntegrationTest.this.getMe(accessTokenIssuer.issue(accountId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(accountId))
				.andExpect(jsonPath("$.name").value("박준하"));
		}

	}

	@Nested
	@DisplayName("이름 변경")
	class ChangeName {

		@Test
		@DisplayName("규칙에 맞는 이름을 저장하면 바뀐 내 정보를 돌려주고 내 정보에도 보인다")
		void changes() throws Exception {
			String token = accessTokenIssuer.issue(accountId);

			patchName(token, "{\"name\":\"Jiwoo\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(accountId))
				.andExpect(jsonPath("$.name").value("Jiwoo"));

			getMe(token).andExpect(jsonPath("$.name").value("Jiwoo"));
		}

		@Test
		@DisplayName("NFD로 분해된 한글 이름은 NFC로 정규화해 저장한다")
		void normalizes() throws Exception {
			String nfd = Normalizer.normalize("홍길동", Normalizer.Form.NFD);

			patchName(accessTokenIssuer.issue(accountId), "{\"name\":\"" + nfd + "\"}").andExpect(status().isOk());

			assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, accountId))
				.isEqualTo("홍길동");
		}

		@ParameterizedTest
		@ValueSource(strings = { "{\"name\":\"\"}", "{\"name\":\"홍길동1\"}", "{\"name\":\"홍 길동\"}",
				"{\"name\":\"ㅋㅋㅋ\"}", "{\"name\":\"🐥\"}", "{\"name\":\"가나다라마바사아자차카타파하가나다라마바사\"}",
				"{\"name\":null}", "{}" })
		@DisplayName("규칙에 맞지 않는 이름은 400 INVALID_NAME이고 이름이 바뀌지 않는다")
		void rejects(String body) throws Exception {
			patchName(accessTokenIssuer.issue(accountId), body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_NAME"));

			assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, accountId))
				.isEqualTo("박준하");
		}

		@Test
		@DisplayName("접근 토큰이 없으면 401이다")
		void unauthenticated() throws Exception {
			mockMvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Jiwoo\"}"))
				.andExpect(status().isUnauthorized());
		}

	}

	private ResultActions getMe(String accessToken) throws Exception {
		return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken));
	}

	private ResultActions patchName(String accessToken, String body) throws Exception {
		return mockMvc.perform(patch("/api/me").header("Authorization", "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body.getBytes(StandardCharsets.UTF_8)));
	}

	private static Map<String, Object> claimsOf(String token) {
		return decodePart(token.split("\\.")[1]);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> decodePart(String part) {
		return JSON.readValue(Base64.getUrlDecoder().decode(part), Map.class);
	}

}
