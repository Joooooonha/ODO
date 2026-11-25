package com.odo.common.error;

import java.sql.Connection;

import javax.sql.DataSource;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB 연결을 대기 시간 안에 받지 못하면 모든 API가 503 SERVER_BUSY를 돌려준다.
 * 12_Account_spec.md의 공통 규칙, 03_Schedule_spec.md의 "503 (통합)".
 * 풀에 연결을 하나만 두고 테스트가 그 연결을 붙잡은 채 API를 부른다. 연결 대기는 HikariCP의 최솟값인 250ms다.
 */
@IntegrationTest
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.connection-timeout=250" })
class ServerBusyIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Test
	@DisplayName("풀의 연결이 모두 쓰이는 동안 DB를 쓰는 API를 부르면 503 SERVER_BUSY다")
	void poolExhausted() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		long account = TestAccounts.create(jdbc, "박준하", clock.instant());
		String bearer = "Bearer " + accessTokenIssuer.issue(account);

		try (Connection held = dataSource.getConnection()) {
			mockMvc.perform(get("/api/teams").header("Authorization", bearer))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVER_BUSY"));
		}
	}

	@Test
	@DisplayName("연결을 돌려준 뒤에는 같은 API가 정상으로 응답한다")
	void recoversAfterRelease() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		long account = TestAccounts.create(jdbc, "박준하", clock.instant());
		String bearer = "Bearer " + accessTokenIssuer.issue(account);

		try (Connection held = dataSource.getConnection()) {
			mockMvc.perform(get("/api/teams").header("Authorization", bearer))
				.andExpect(status().isServiceUnavailable());
		}

		mockMvc.perform(get("/api/teams").header("Authorization", bearer)).andExpect(status().isOk());
	}

}
