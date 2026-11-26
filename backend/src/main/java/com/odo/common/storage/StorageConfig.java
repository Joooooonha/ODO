package com.odo.common.storage;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 저장소 빈. 테스트는 메모리 저장소를 @Primary로 둔다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

	@Bean(destroyMethod = "close")
	S3ObjectStorage objectStorage(StorageProperties properties, Clock clock) {
		return new S3ObjectStorage(properties, clock);
	}

}
