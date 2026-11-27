package com.odo.notification.infrastructure;

import com.odo.notification.domain.repository.NotificationInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * NotificationInboxRepository의 JDBC 구현. 순번 올리기는 INSERT ... ON CONFLICT ... RETURNING 한 문장이라 JDBC로 쓴다.
 * 호출하는 쪽의 트랜잭션 연결에서 돈다.
 */
@Repository
@RequiredArgsConstructor
class JdbcNotificationInboxRepository implements NotificationInboxRepository {

	private final JdbcTemplate jdbc;

	@Override
	public long increaseLastSeq(long accountId, int count) {
		// 행이 아직 없을 때 두 트랜잭션이 동시에 넣어도, 뒤의 것은 유니크 인덱스에서 앞의 커밋을 기다린 뒤 UPDATE로 바뀐다.
		Long lastSeq = jdbc.queryForObject("""
				INSERT INTO notification_inbox (account_id, last_seq) VALUES (?, ?)
				ON CONFLICT (account_id) DO UPDATE SET last_seq = notification_inbox.last_seq + EXCLUDED.last_seq
				RETURNING last_seq
				""", Long.class, accountId, count);
		return lastSeq;
	}

	@Override
	public void markSeen(long accountId, long seq) {
		jdbc.update("UPDATE notification_inbox SET seen_seq = GREATEST(seen_seq, LEAST(?, last_seq)) WHERE account_id = ?",
				seq, accountId);
	}

}
