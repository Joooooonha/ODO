package com.odo.common.crypto;

import java.util.Base64;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.crypto 설정. 운영에서는 환경 변수(ODO_DATA_KEY_V1, ODO_DATA_KEY_CURRENT)로 넣는다.
 * 키는 버전별로 32바이트를 base64로 받는다. 지금 쓰는 버전은 새로 암호화할 때 쓰고, 옛 버전은 복호화에만 쓴다.
 * 키가 하나라도 틀리면 서버가 뜨지 않는다. 옛 키가 틀린 것을 복호화할 때가 아니라 뜰 때 알기 위해서다.
 * 오류 메시지에는 키 값을 넣지 않는다.
 */
@ConfigurationProperties("odo.crypto")
public record CryptoProperties(
		String currentKeyVersion,
		Map<String, String> dataKeys) {

	private static final int KEY_BYTES = 32;

	public CryptoProperties {
		if (currentKeyVersion == null || currentKeyVersion.isBlank()) {
			throw new IllegalStateException("odo.crypto.current-key-version이 없다");
		}
		if (dataKeys == null || !dataKeys.containsKey(currentKeyVersion)) {
			throw new IllegalStateException("odo.crypto.data-keys에 지금 쓰는 버전 " + currentKeyVersion + "의 키가 없다");
		}
		dataKeys.forEach(CryptoProperties::decode);
		dataKeys = Map.copyOf(dataKeys);
	}

	/** 그 버전의 키 32바이트. 없는 버전이면 null이다. */
	public byte[] keyBytes(String version) {
		String encoded = dataKeys.get(version);
		return encoded == null ? null : decode(version, encoded);
	}

	private static byte[] decode(String version, String encoded) {
		if (encoded == null || encoded.isBlank()) {
			throw new IllegalStateException("odo.crypto.data-keys." + version + "가 비어 있다");
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(encoded.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("odo.crypto.data-keys." + version + "가 base64가 아니다");
		}
		if (key.length != KEY_BYTES) {
			throw new IllegalStateException("odo.crypto.data-keys." + version + "는 32바이트여야 한다");
		}
		return key;
	}

}
