package com.odo.common.crypto;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 암호화. 저장 형식은 "v1:" + base64(nonce 12바이트 + 암호문 + 인증 태그).
 * 03_Schedule_spec.md의 "연습실 비밀번호 암호화"와 "암호화 (단위)".
 */
class DataCipherTest {

	private static final String KEY_A = Base64.getEncoder().encodeToString(bytes(1));

	private static final String KEY_B = Base64.getEncoder().encodeToString(bytes(2));

	private static final DataCipher CIPHER = new DataCipher(new CryptoProperties("v1", Map.of("v1", KEY_A)));

	private static byte[] bytes(int seed) {
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) (seed * 31 + i);
		}
		return key;
	}

	@Test
	@DisplayName("암호문은 지금 쓰는 버전으로 시작하고, 복호화하면 원래 값이 나온다")
	void roundTrip() {
		String stored = CIPHER.encrypt("현관 1234#");

		assertThat(stored).startsWith("v1:");
		assertThat(stored).doesNotContain("1234");
		assertThat(CIPHER.decrypt(stored)).isEqualTo("현관 1234#");
	}

	@Test
	@DisplayName("같은 평문도 매번 다른 암호문이 된다. nonce를 매번 새로 만들기 때문이다")
	void differentEachTime() {
		assertThat(CIPHER.encrypt("1234")).isNotEqualTo(CIPHER.encrypt("1234"));
	}

	@Test
	@DisplayName("null은 암호화와 복호화 모두 null이다. 비밀번호가 없는 연습실이다")
	void nullValue() {
		assertThat(CIPHER.encrypt(null)).isNull();
		assertThat(CIPHER.decrypt(null)).isNull();
	}

	@Test
	@DisplayName("암호문이 바뀌면 엉뚱한 값을 내놓지 않고 오류가 난다")
	void tampered() {
		String stored = CIPHER.encrypt("1234");
		byte[] raw = Base64.getDecoder().decode(stored.substring("v1:".length()));
		raw[raw.length - 1] ^= 1;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

		assertThatThrownBy(() -> CIPHER.decrypt(tampered)).isInstanceOf(DecryptionFailedException.class);
	}

	@Test
	@DisplayName("다른 키로 풀면 오류가 난다")
	void wrongKey() {
		DataCipher other = new DataCipher(new CryptoProperties("v1", Map.of("v1", KEY_B)));
		String stored = CIPHER.encrypt("1234");

		assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(DecryptionFailedException.class);
	}

	@Test
	@DisplayName("키를 바꾸면 새 값은 새 버전으로 암호화되고, 옛 버전의 암호문은 옛 키로 풀린다")
	void rotation() {
		String oldStored = CIPHER.encrypt("옛 비밀번호");
		DataCipher rotated = new DataCipher(new CryptoProperties("v2", Map.of("v1", KEY_A, "v2", KEY_B)));

		String newStored = rotated.encrypt("새 비밀번호");

		assertThat(newStored).startsWith("v2:");
		assertThat(rotated.decrypt(newStored)).isEqualTo("새 비밀번호");
		assertThat(rotated.decrypt(oldStored)).isEqualTo("옛 비밀번호");
	}

	@Test
	@DisplayName("모르는 버전이거나 버전이 없는 값은 오류가 난다")
	void unknownVersion() {
		String stored = CIPHER.encrypt("1234");
		String unknown = "v9:" + stored.substring("v1:".length());

		assertThatThrownBy(() -> CIPHER.decrypt(unknown)).isInstanceOf(DecryptionFailedException.class);
		assertThatThrownBy(() -> CIPHER.decrypt("1234")).isInstanceOf(DecryptionFailedException.class);
		assertThatThrownBy(() -> CIPHER.decrypt("v1:not-base64!!")).isInstanceOf(DecryptionFailedException.class);
	}

	@Test
	@DisplayName("복호화 오류 메시지에 저장된 값을 넣지 않는다. 로그에 값이 남지 않게 하기 위해서다")
	void messageHasNoValue() {
		String stored = CIPHER.encrypt("1234");
		String tampered = stored.substring(0, stored.length() - 2) + "AA";

		assertThatThrownBy(() -> CIPHER.decrypt(tampered)).isInstanceOf(DecryptionFailedException.class)
			.message()
			.doesNotContain(tampered);
	}

	@Test
	@DisplayName("JPA 변환기는 저장할 때 암호화하고 읽을 때 복호화한다")
	void converter() {
		EncryptedStringConverter converter = new EncryptedStringConverter(CIPHER);

		String column = converter.convertToDatabaseColumn("1234");

		assertThat(column).startsWith("v1:");
		assertThat(converter.convertToEntityAttribute(column)).isEqualTo("1234");
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

}
