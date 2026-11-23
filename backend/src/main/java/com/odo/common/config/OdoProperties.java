package com.odo.common.config;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.* 설정. 운영에서는 환경 변수로 넣는다.
 */
@ConfigurationProperties("odo")
public record OdoProperties(
		String frontendBaseUrl,
		Auth auth) {

	private static final int MIN_SECRET_BYTES = 32;

	public OdoProperties {
		if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
			throw new IllegalStateException("odo.frontend-base-url이 없다");
		}
		if (auth == null) {
			throw new IllegalStateException("odo.auth 설정이 없다");
		}
		frontendBaseUrl = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
				: frontendBaseUrl;
	}

	public record Auth(
			String jwtSecret,
			String oauthCookieSecret) {

		public Auth {
			requireSecret("odo.auth.jwt-secret", jwtSecret);
			requireSecret("odo.auth.oauth-cookie-secret", oauthCookieSecret);
			if (jwtSecret.equals(oauthCookieSecret)) {
				throw new IllegalStateException("JWT 비밀키와 OAuth 상태 쿠키 비밀키는 달라야 한다");
			}
		}

		public byte[] jwtSecretBytes() {
			return jwtSecret.getBytes(StandardCharsets.UTF_8);
		}

		public byte[] oauthCookieSecretBytes() {
			return oauthCookieSecret.getBytes(StandardCharsets.UTF_8);
		}

		private static void requireSecret(String name, String value) {
			if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
				throw new IllegalStateException(name + "는 32바이트 이상이어야 한다");
			}
		}

	}

}
