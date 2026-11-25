package com.odo.schedule.presentation;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 도착 시각 필터를 가장 앞 순서로 등록한다. Spring Security 필터(기본 순서 -100)보다 먼저 실행된다.
 * 인증 확인도 도착 시각을 찍은 뒤에 하므로, 인증에 걸린 시간이 순서에 섞이지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class VoteArrivalFilterConfig {

	@Bean
	FilterRegistrationBean<VoteArrivalFilter> voteArrivalFilterRegistration(Clock clock) {
		FilterRegistrationBean<VoteArrivalFilter> registration = new FilterRegistrationBean<>(
				new VoteArrivalFilter(clock));
		registration.addUrlPatterns("/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

}
