package com.odo.auth.presentation;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.odo.auth.application.KakaoLoginService.LoginResult;
import com.odo.auth.application.KakaoLoginService;
import com.odo.auth.application.RefreshTokenService;
import com.odo.auth.domain.RedirectPathValidator;
import com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository;
import com.odo.common.config.OdoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 성공 뒤 계정을 찾거나 만들고, 갱신 토큰 쿠키를 발급해 프론트로 보낸다.
 * 접근 토큰은 URL에 싣지 않는다. 프론트가 앱을 열 때처럼 갱신 API를 불러 받는다.
 * <p>
 * 이 처리 중 오류가 나도 콜백 응답은 302로, 로그인 실패 화면으로 보낸다. 오류가 필터 밖으로 나가면 사용자가 API 도메인의 오류 화면에 남는다.
 * 계정은 만들어졌는데 갱신 토큰 발급만 실패했다면, 다시 로그인할 때 기존 회원으로 들어오므로 되돌리지 않는다.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

	private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);

	private final KakaoLoginService kakaoLoginService;

	private final RefreshTokenService refreshTokenService;

	private final String frontendBaseUrl;

	public LoginSuccessHandler(KakaoLoginService kakaoLoginService, RefreshTokenService refreshTokenService,
			OdoProperties properties) {
		this.kakaoLoginService = kakaoLoginService;
		this.refreshTokenService = refreshTokenService;
		this.frontendBaseUrl = properties.frontendBaseUrl();
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		try {
			completeLogin(request, response, authentication);
		}
		catch (RuntimeException ex) {
			log.warn("카카오 로그인 뒤 처리 실패. 로그인 실패 화면으로 보낸다", ex);
			response.sendRedirect(frontendBaseUrl + LoginFailureHandler.LOGIN_FAILED_PATH);
		}
	}

	private void completeLogin(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException {
		OAuth2User user = (OAuth2User) authentication.getPrincipal();
		LoginResult result = kakaoLoginService.login(kakaoUserId(user), nickname(user));

		RefreshTokenCookies.addIssue(response, refreshTokenService.issueForNewLogin(result.accountId()));

		// 쿠키 저장소가 검사해 넣은 값이지만, 여기서 한 번 더 검사한다
		Object attribute = request.getAttribute(CookieOAuth2AuthorizationRequestRepository.REDIRECT_ATTRIBUTE);
		String redirect = RedirectPathValidator.safePathOrDefault(attribute instanceof String value ? value : null);
		if (result.newAccount()) {
			response.sendRedirect(
					frontendBaseUrl + "/welcome?next=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8));
		}
		else {
			response.sendRedirect(frontendBaseUrl + redirect);
		}
	}

	private static String kakaoUserId(OAuth2User user) {
		Object id = user.getAttributes().get("id");
		if (id == null) {
			throw new IllegalStateException("카카오 사용자 정보에 id가 없다");
		}
		return String.valueOf(id);
	}

	/** kakao_account.profile.nickname. 닉네임 제공에 동의하지 않았으면 null. */
	private static String nickname(OAuth2User user) {
		if (user.getAttributes().get("kakao_account") instanceof Map<?, ?> account
				&& account.get("profile") instanceof Map<?, ?> profile
				&& profile.get("nickname") instanceof String nickname) {
			return nickname;
		}
		return null;
	}

}
