package com.odo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트에서 R2 대신 메모리 저장소를 쓴다. 시간은 MutableClock을 따른다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestStorageConfiguration {

	@Bean
	@Primary
	InMemoryObjectStorage inMemoryObjectStorage(MutableClock clock) {
		return new InMemoryObjectStorage(clock);
	}

}
