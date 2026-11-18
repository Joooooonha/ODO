package com.odo.support;

import java.util.Optional;

import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 응답의 Set-Cookie 헤더를 이름으로 찾는다.
 * 쿠키 속성(HttpOnly, SameSite 등)까지 검사하려고 Cookie 객체가 아니라 헤더 문자열을 본다.
 */
public final class TestCookies {

	private TestCookies() {
	}

	public static Optional<String> setCookieHeader(MockHttpServletResponse response, String name) {
		return response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith(name + "=")).findFirst();
	}

	public static String requireSetCookieHeader(MockHttpServletResponse response, String name) {
		return setCookieHeader(response, name)
			.orElseThrow(() -> new AssertionError(name + " Set-Cookie 헤더가 없다. 헤더: " + response.getHeaders("Set-Cookie")));
	}

	public static String value(String setCookieHeader) {
		String nameValue = setCookieHeader.split(";", 2)[0];
		return nameValue.substring(nameValue.indexOf('=') + 1);
	}

}
