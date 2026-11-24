package com.odo.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * DB에 저장하는 값을 AES-256-GCM으로 암호화한다. 저장 형식은 "버전:" + base64(nonce 12바이트 + 암호문 + 인증 태그)다.
 * 앞의 버전을 보고 어느 키로 풀지 고르므로, 키를 바꿔도 옛 암호문을 읽을 수 있다.
 * GCM은 암호문이 바뀌거나 키가 틀리면 인증 태그 확인에서 실패한다. 엉뚱한 평문을 내놓지 않는다.
 * 03_Schedule_spec.md의 "연습실 비밀번호 암호화".
 */
@Component
public class DataCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final char VERSION_SEPARATOR = ':';

	private final CryptoProperties properties;

	private final SecureRandom random = new SecureRandom();

	public DataCipher(CryptoProperties properties) {
		this.properties = properties;
	}

	/** 지금 쓰는 버전의 키로 암호화한다. null이면 null이다. 같은 평문도 nonce가 매번 달라 암호문이 다르다. */
	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		String version = properties.currentKeyVersion();
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, properties.keyBytes(version), nonce);
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] stored = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
			return version + VERSION_SEPARATOR + Base64.getEncoder().encodeToString(stored);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("암호화에 실패했다", ex);
		}
	}

	/** 저장된 값의 버전에 맞는 키로 푼다. null이면 null이다. 풀지 못하면 DecryptionFailedException. */
	public String decrypt(String stored) {
		if (stored == null) {
			return null;
		}
		int separator = stored.indexOf(VERSION_SEPARATOR);
		if (separator <= 0) {
			throw new DecryptionFailedException("암호문에 키 버전이 없다");
		}
		String version = stored.substring(0, separator);
		byte[] key = properties.keyBytes(version);
		if (key == null) {
			throw new DecryptionFailedException("모르는 키 버전이다: " + version);
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(stored.substring(separator + 1));
		}
		catch (IllegalArgumentException ex) {
			throw new DecryptionFailedException("암호문이 base64가 아니다");
		}
		if (raw.length <= NONCE_BYTES) {
			throw new DecryptionFailedException("암호문이 너무 짧다");
		}
		try {
			Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOfRange(raw, 0, NONCE_BYTES));
			byte[] plain = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			// 예외 메시지에도 값이 없지만, 원인 예외는 그대로 남겨 키 문제인지 알 수 있게 한다.
			throw new DecryptionFailedException("암호문을 풀지 못했다. 키가 틀렸거나 암호문이 바뀌었다", ex);
		}
	}

	private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
		return cipher;
	}

}
