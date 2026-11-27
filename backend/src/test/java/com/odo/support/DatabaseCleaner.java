package com.odo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 사이에 계정, 팀 스페이스, 공연, 일정, 연습 영상, 알림 테이블을 비운다.
 */
public final class DatabaseCleaner {

	private DatabaseCleaner() {
	}

	public static void clean(JdbcTemplate jdbc) {
		jdbc.execute("TRUNCATE TABLE practice_video, vote_request, notification, notification_inbox, schedule_participant, schedule, practice_room, song, performance_member, performance, join_request, team_member, team, refresh_token, social_login, account RESTART IDENTITY CASCADE");
	}

}
