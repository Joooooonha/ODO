package com.odo.auth.presentation;

import java.util.List;
import java.util.Map;

import com.odo.auth.application.KakaoLoginService;
import com.odo.auth.application.KakaoLoginService.LoginResult;
import com.odo.auth.application.RefreshTokenService;
import com.odo.common.config.OdoProperties;
import com.odo.support.TestCookies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그인 성공 처리 중 오류가 나도 응답은 302이고 로그인 실패 화면으로 보낸다(12_Account_spec.md의 카카오 콜백).
 */
class LoginSuccessHandlerTest {

	private static final String FRONT = "http://front.test";

	private static final String LOGIN_FAILED = FRONT + "/login?error=login_failed";

	private final KakaoLoginService kakaoLoginService = mock(KakaoLoginService.class);

	private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

	private final LoginSuccessHandler handler = new LoginSuccessHandler(kakaoLoginService, refreshTokenService,
			new OdoProperties(FRONT, new OdoProperties.Auth("j".repeat(32), "o".repeat(32))));

	private final MockHttpServletRequest request = new MockHttpServletRequest();

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@Test
	@DisplayName("계정을 찾거나 만들다 DB 오류가 나면 로그인 실패 화면으로 302 redirect하고 갱신 토큰 쿠키를 발급하지 않는다")
	void accountFailure() throws Exception {
		when(kakaoLoginService.login(any(), any())).thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));

		handler.onAuthenticationSuccess(request, response, kakaoUser(Map.of("id", 123L)));

		assertRedirectedToLoginFailed();
		verify(refreshTokenService, never()).issueForNewLogin(anyLong());
	}

	@Test
	@DisplayName("갱신 토큰을 발급하다 DB 오류가 나면 로그인 실패 화면으로 302 redirect하고 갱신 토큰 쿠키를 발급하지 않는다")
	void refreshTokenFailure() throws Exception {
		when(kakaoLoginService.login(any(), any())).thenReturn(new LoginResult(1L, false));
		when(refreshTokenService.issueForNewLogin(1L)).thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));

		handler.onAuthenticationSuccess(request, response, kakaoUser(Map.of("id", 123L)));

		assertRedirectedToLoginFailed();
	}

	@Test
	@DisplayName("카카오 사용자 정보에 id가 없으면 로그인 실패 화면으로 302 redirect한다")
	void missingKakaoId() throws Exception {
		handler.onAuthenticationSuccess(request, response, kakaoUser(Map.of("sub", "x")));

		assertRedirectedToLoginFailed();
		verify(kakaoLoginService, never()).login(any(), any());
	}

	private void assertRedirectedToLoginFailed() {
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl()).isEqualTo(LOGIN_FAILED);
		assertThat(TestCookies.setCookieHeader(response, RefreshTokenCookies.NAME)).isEmpty();
	}

	private static Authentication kakaoUser(Map<String, Object> attributes) {
		String nameKey = attributes.keySet().iterator().next();
		return new TestingAuthenticationToken(new DefaultOAuth2User(List.of(), attributes, nameKey), null);
	}

}
