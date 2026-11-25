package com.odo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 로컬 실행(bootTestRun)에서 R2 대신 S3 호환 저장소 컨테이너(Adobe S3Mock)를 띄운다. 08_PracticeVideo_spec.md의 "저장소".
 * 화면이 브라우저에서 서명된 주소로 바로 올리고 재생할 수 있다(S3Mock이 CORS에 답한다). 서명은 검사하지 않는다.
 * 통합 테스트는 메모리 저장소를 쓰므로 이 구성을 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class LocalStorageConfiguration {

	private static final String BUCKET = "odo-local";

	@Bean
	GenericContainer<?> s3MockContainer() {
		return new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3")).withExposedPorts(9090)
			.withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET);
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(GenericContainer<?> s3MockContainer) {
		return registry -> {
			registry.add("odo.storage.endpoint",
					() -> "http://" + s3MockContainer.getHost() + ":" + s3MockContainer.getMappedPort(9090));
			registry.add("odo.storage.bucket", () -> BUCKET);
			registry.add("odo.storage.access-key-id", () -> "local-access-key");
			registry.add("odo.storage.secret-access-key", () -> "local-secret-key");
		};
	}

}
