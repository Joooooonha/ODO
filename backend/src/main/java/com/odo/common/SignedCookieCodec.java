package com.odo.common;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 쿠키 값에 HMAC-SHA256 서명과 만료 시각을 붙이고, 확인한 뒤 본문을 꺼낸다.
 * 형식: base64url("만료 epoch초:본문").base64url(서명)
 * <p>
 * 서명은 인코딩된 문자열에 대해 계산하고, 비교도 인코딩된 문자열끼리 한다.
 * base64 끝 글자의 쓰지 않는 비트만 바꾼 값이 같은 바이트로 풀려 통과하는 일을 막는다.
 */
public class SignedCookieCodec {

	private static final String ALGORITHM = "HmacSHA256";

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;

	private final Clock clock;

	public SignedCookieCodec(byte[] secret, Clock clock) {
		this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
		this.clock = clock;
	}

	/** payload에 지금부터 ttl 뒤의 만료 시각을 붙여 서명한 쿠키 값. */
	public String encode(String payload, Duration ttl) {
		long expiresAt = clock.instant().plus(ttl).getEpochSecond();
		String body = ENCODER.encodeToString((expiresAt + ":" + payload).getBytes(StandardCharsets.UTF_8));
		return body + "." + sign(body);
	}

	/** 서명이 맞고 만료되지 않았을 때만 본문. 그 밖에는 빈 값. */
	public Optional<String> decode(String cookieValue) {
		if (cookieValue == null || cookieValue.isEmpty()) {
			return Optional.empty();
		}
		String[] parts = cookieValue.split("\\.", -1);
		if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
			return Optional.empty();
		}
		byte[] expected = sign(parts[0]).getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(expected, parts[1].getBytes(StandardCharsets.US_ASCII))) {
			return Optional.empty();
		}
		// 서명이 맞은 뒤에만 본문을 읽는다
		String body;
		try {
			body = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		int separator = body.indexOf(':');
		if (separator <= 0) {
			return Optional.empty();
		}
		long expiresAt;
		try {
			expiresAt = Long.parseLong(body.substring(0, separator));
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
		if (clock.instant().getEpochSecond() > expiresAt) {
			return Optional.empty();
		}
		return Optional.of(body.substring(separator + 1));
	}

	private String sign(String body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("쿠키 서명 실패", ex);
		}
	}

}
