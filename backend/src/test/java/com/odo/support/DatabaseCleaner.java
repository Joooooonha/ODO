package com.odo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 사이에 계정, 팀 스페이스, 공연, 알림 이벤트 테이블을 비운다.
 */
public final class DatabaseCleaner {

	private DatabaseCleaner() {
	}

	public static void clean(JdbcTemplate jdbc) {
		jdbc.execute("TRUNCATE TABLE notification_event, song, performance_member, performance, join_request, team_member, team, refresh_token, social_login, account RESTART IDENTITY CASCADE");
	}

}
