package com.odo.auth.presentation;

import java.io.IOException;

import com.odo.common.config.OdoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 실패(사용자 취소, state 불일치, OAuth 상태 쿠키 없음이나 위조, 카카오 오류).
 * 실패 이유는 로그에만 남기고 프론트에는 하나의 값으로 알린다.
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

	/** 로그인 실패 화면. 성공 처리 중 오류가 날 때도 이 주소로 보낸다. */
	public static final String LOGIN_FAILED_PATH = "/login?error=login_failed";

	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

	private final String loginFailedUrl;

	public LoginFailureHandler(OdoProperties properties) {
		this.loginFailedUrl = properties.frontendBaseUrl() + LOGIN_FAILED_PATH;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		log.info("카카오 로그인 실패: {}", exception.getMessage());
		response.sendRedirect(loginFailedUrl);
	}

}
