package com.odo.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.odo.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Testcontainers PostgreSQL 17과 시간을 조절하는 Clock으로 앱 전체를 띄운다.
 * 모든 통합 테스트가 같은 구성을 써서 Spring 컨텍스트와 컨테이너를 한 번만 띄운다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
public @interface IntegrationTest {

}
