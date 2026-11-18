package com.odo.auth.oauth;

import com.odo.support.IntegrationTest;
import com.odo.support.TestCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import static com.odo.auth.oauth.CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security 필터를 거치는 로그인 시작과 콜백.
 * 카카오 서버를 부르기 전에 끝나는 경우(state 확인, 쿠키 확인, 사용자 취소)만 다룬다.
 * 카카오와 토큰을 교환하는 성공 경로는 실제 카카오로 직접 확인한다.
 */
@IntegrationTest
class LoginFlowIntegrationTest {

	private static final String LOGIN_FAILED = "http://localhost:5173/login?error=login_failed";

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("로그인 시작은 카카오 인가 화면으로 보내고 OAuth 상태 쿠키를 발급하며 서버 세션을 만들지 않는다")
	void start() throws Exception {
		MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao").param("redirect", "/invite/abc"))
			.andExpect(status().isFound())
			.andReturn();

		String location = result.getResponse().getRedirectedUrl();
		assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize?");
		var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		assertThat(query.getFirst("client_id")).isEqualTo("test-kakao-client-id");
		assertThat(query.getFirst("response_type")).isEqualTo("code");
		assertThat(query.getFirst("state")).isNotBlank();

		TestCookies.requireSetCookieHeader(result.getResponse(), COOKIE_NAME);
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(TestCookies.setCookieHeader(result.getResponse(), "JSESSIONID")).isEmpty();
	}

	@Test
	@DisplayName("콜백의 state가 쿠키의 state와 다르면 로그인 실패 화면으로 보낸다")
	void stateMismatch() throws Exception {
		LoginStart start = startLogin();

		MvcResult result = mockMvc
			.perform(get("/login/oauth2/code/kakao").param("code", "any-code")
				.param("state", "attacker-state")
				.cookie(start.cookie()))
			.andExpect(status().isFound())
			.andReturn();

		assertLoginFailed(result.getResponse());
	}

	@Test
	@DisplayName("OAuth 상태 쿠키 없이 콜백이 오면 로그인 실패 화면으로 보낸다")
	void missingCookie() throws Exception {
		MvcResult result = mockMvc
			.perform(get("/login/oauth2/code/kakao").param("code", "any-code").param("state", "any-state"))
			.andExpect(status().isFound())
			.andReturn();

		assertLoginFailed(result.getResponse());
	}

	@Test
	@DisplayName("변조된 OAuth 상태 쿠키로 콜백이 오면 로그인 실패 화면으로 보낸다")
	void tamperedCookie() throws Exception {
		LoginStart start = startLogin();
		String value = start.cookie().getValue();
		int middle = value.indexOf('.') / 2;
		String tampered = value.substring(0, middle) + (value.charAt(middle) == 'A' ? 'B' : 'A')
				+ value.substring(middle + 1);

		MvcResult result = mockMvc
			.perform(get("/login/oauth2/code/kakao").param("code", "any-code")
				.param("state", start.state())
				.cookie(new Cookie(COOKIE_NAME, tampered)))
			.andExpect(status().isFound())
			.andReturn();

		assertLoginFailed(result.getResponse());
	}

	@Test
	@DisplayName("사용자가 카카오에서 취소하면 로그인 실패 화면으로 보낸다")
	void userCancelled() throws Exception {
		LoginStart start = startLogin();

		MvcResult result = mockMvc
			.perform(get("/login/oauth2/code/kakao").param("error", "access_denied")
				.param("state", start.state())
				.cookie(start.cookie()))
			.andExpect(status().isFound())
			.andReturn();

		assertLoginFailed(result.getResponse());
	}

	/** 로그인을 시작해 발급된 OAuth 상태 쿠키와, 카카오로 보낸 state */
	private record LoginStart(Cookie cookie, String state) {
	}

	private LoginStart startLogin() throws Exception {
		MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao").param("redirect", "/invite/abc"))
			.andReturn();
		String state = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
			.build()
			.getQueryParams()
			.getFirst("state");
		String setCookie = TestCookies.requireSetCookieHeader(result.getResponse(), COOKIE_NAME);
		return new LoginStart(new Cookie(COOKIE_NAME, TestCookies.value(setCookie)), state);
	}

	private static void assertLoginFailed(MockHttpServletResponse response) {
		assertThat(response.getRedirectedUrl()).isEqualTo(LOGIN_FAILED);
		assertThat(TestCookies.setCookieHeader(response, "ODO_RT")).isEmpty();
	}

}
