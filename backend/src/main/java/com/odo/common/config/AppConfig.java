package com.odo.common.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OdoProperties.class)
@EnableScheduling
public class AppConfig {

	/** 시간은 모두 이 Clock으로 얻는다. 테스트에서 시간을 바꿔 만료 조건을 검증하기 위해서다. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
