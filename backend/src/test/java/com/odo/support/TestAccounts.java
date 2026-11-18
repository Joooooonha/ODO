package com.odo.support;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 로그인 흐름을 거치지 않고 계정을 바로 만든다. 토큰과 내 정보 API 테스트의 준비용이다.
 */
public final class TestAccounts {

	private TestAccounts() {
	}

	public static long create(JdbcTemplate jdbc, String name, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		return jdbc.queryForObject(
				"INSERT INTO account (name, created_at, updated_at) VALUES (?, ?, ?) RETURNING id", Long.class, name,
				timestamp, timestamp);
	}

}
