package com.odo.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 엔티티의 문자열 칼럼을 저장할 때 암호화하고 읽을 때 복호화한다. 칼럼에 @Convert(converter = ...)로 붙인다.
 * 서비스와 API 코드는 평문만 다룬다. Hibernate가 Spring 빈으로 만들어 DataCipher를 주입한다.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private final DataCipher cipher;

	public EncryptedStringConverter(DataCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return cipher.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		return cipher.decrypt(dbData);
	}

}
