package com.odo.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.storage.* 설정. 08_PracticeVideo_spec.md의 "설정". 운영에서는 환경 변수로 넣고, 비어 있으면 서버가 뜨지 않는다.
 *
 * @param endpoint R2의 S3 API 주소(https://{계정 ID}.r2.cloudflarestorage.com)
 * @param bucket 공개 접근을 끈 버킷
 * @param accessKeyId 이 버킷에만 읽기와 쓰기가 되는 API 토큰의 키
 * @param secretAccessKey 그 비밀값
 */
@ConfigurationProperties("odo.storage")
public record StorageProperties(
		String endpoint,
		String bucket,
		String accessKeyId,
		String secretAccessKey) {

	public StorageProperties {
		requireValue("odo.storage.endpoint", endpoint);
		requireValue("odo.storage.bucket", bucket);
		requireValue("odo.storage.access-key-id", accessKeyId);
		requireValue("odo.storage.secret-access-key", secretAccessKey);
	}

	/** 비밀값이 로그에 남지 않게 한다. */
	@Override
	public String toString() {
		return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + "]";
	}

	private static void requireValue(String name, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + "가 없다");
		}
	}

}
