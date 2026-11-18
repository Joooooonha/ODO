package com.odo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 사이에 계정 관련 테이블을 비운다.
 */
public final class DatabaseCleaner {

	private DatabaseCleaner() {
	}

	public static void clean(JdbcTemplate jdbc) {
		jdbc.execute("TRUNCATE TABLE refresh_token, social_login, account RESTART IDENTITY CASCADE");
	}

}
