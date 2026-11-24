package com.odo.common.crypto;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * odo.crypto 설정. 지금 쓰는 키가 비어 있거나 32바이트가 아니면 서버가 뜨지 않는다.
 * 03_Schedule_spec.md의 "연습실 비밀번호 암호화"와 "암호화 (단위)".
 */
class CryptoPropertiesTest {

	private static final String KEY_32 = Base64.getEncoder().encodeToString(new byte[32]);

	private static final String KEY_16 = Base64.getEncoder().encodeToString(new byte[16]);

	@Test
	@DisplayName("지금 쓰는 버전과 그 버전의 32바이트 키가 있으면 통과하고, 키를 바이트로 돌려준다")
	void valid() {
		CryptoProperties properties = new CryptoProperties("v1", Map.of("v1", KEY_32));

		assertThat(properties.keyBytes("v1")).hasSize(32);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	@DisplayName("지금 쓰는 버전이 비어 있으면 실패한다")
	void currentVersionMissing(String currentVersion) {
		assertThatThrownBy(() -> new CryptoProperties(currentVersion, Map.of("v1", KEY_32)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("지금 쓰는 버전의 키가 없으면 실패한다")
	void currentKeyMissing() {
		assertThatThrownBy(() -> new CryptoProperties("v2", Map.of("v1", KEY_32)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CryptoProperties("v1", null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("지금 쓰는 키가 비어 있으면 실패한다. 환경 변수를 넣지 않은 경우다")
	void currentKeyBlank() {
		Map<String, String> keys = new HashMap<>();
		keys.put("v1", "");

		assertThatThrownBy(() -> new CryptoProperties("v1", keys)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("키가 base64가 아니거나 32바이트가 아니면 실패한다")
	void invalidKey() {
		assertThatThrownBy(() -> new CryptoProperties("v1", Map.of("v1", "not base64 !!")))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CryptoProperties("v1", Map.of("v1", KEY_16)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("옛 버전의 키도 32바이트가 아니면 실패한다. 복호화할 때가 아니라 뜰 때 알기 위해서다")
	void oldKeyInvalid() {
		assertThatThrownBy(() -> new CryptoProperties("v2", Map.of("v1", KEY_16, "v2", KEY_32)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("실패 메시지에 키 값을 넣지 않는다")
	void messageHasNoKey() {
		String wrongKey = Base64.getEncoder().encodeToString("short-secret-value".getBytes());

		assertThatThrownBy(() -> new CryptoProperties("v1", Map.of("v1", wrongKey)))
			.isInstanceOf(IllegalStateException.class)
			.message()
			.doesNotContain(wrongKey);
	}

}
