package com.odo.auth.token;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 갱신 토큰 쿠키 ODO_RT.
 * JS가 읽지 못하게 HttpOnly, 다른 사이트의 요청에 실리지 않게 SameSite=Strict, 갱신과 로그아웃에만 실리게 Path=/api/auth.
 */
public final class RefreshTokenCookies {

	public static final String NAME = "ODO_RT";

	public static final String PATH = "/api/auth";

	private RefreshTokenCookies() {
	}

	public static ResponseCookie issue(String rawToken) {
		return base(rawToken).maxAge(RefreshTokenService.TTL).build();
	}

	public static ResponseCookie delete() {
		return base("").maxAge(0).build();
	}

	public static void addIssue(HttpServletResponse response, String rawToken) {
		response.addHeader(HttpHeaders.SET_COOKIE, issue(rawToken).toString());
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
	}

}
