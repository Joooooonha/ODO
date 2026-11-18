package com.odo.support;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 입장 요청과 승인을 거치지 않고 팀원을 바로 넣는다. 팀 스페이스 조회와 권한 테스트의 준비용이다.
 */
public final class TestTeams {

	private TestTeams() {
	}

	public static void addMember(JdbcTemplate jdbc, long teamId, long accountId, Integer generation, Instant joinedAt) {
		Timestamp timestamp = Timestamp.from(joinedAt);
		jdbc.update(
				"INSERT INTO team_member (team_id, account_id, role, generation, joined_at, updated_at) VALUES (?, ?, 'MEMBER', ?, ?, ?)",
				teamId, accountId, generation, timestamp, timestamp);
	}

}
