package com.odo.auth.oauth;

import java.util.regex.Pattern;

/**
 * 로그인 뒤 돌아갈 주소 검사. 12_Account_spec.md의 "돌아갈 주소 검사".
 * 다른 사이트로 보내는 데 악용되지 않도록(open redirect) 우리 프론트 안의 경로만 허용한다.
 */
public final class RedirectPathValidator {

	public static final String DEFAULT_PATH = "/";

	private static final int MAX_LENGTH = 200;

	/** /로 시작하고 경로 문자(A-Z a-z 0-9 - . _ ~ /)만. ?, #, :, \, %, 공백은 허용하지 않는다. */
	private static final Pattern ALLOWED = Pattern.compile("^/[A-Za-z0-9\\-._~/]*$");

	private RedirectPathValidator() {
	}

	/** 허용된 경로면 그대로, 아니면 DEFAULT_PATH. */
	public static String sanitize(String redirect) {
		if (redirect == null || redirect.isEmpty() || redirect.length() > MAX_LENGTH) {
			return DEFAULT_PATH;
		}
		// 브라우저는 //evil.com을 다른 도메인으로 해석한다
		if (redirect.startsWith("//")) {
			return DEFAULT_PATH;
		}
		return ALLOWED.matcher(redirect).matches() ? redirect : DEFAULT_PATH;
	}

}
