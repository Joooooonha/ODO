package com.odo.notification.infrastructure;

import java.util.List;

import com.odo.notification.domain.entity.Notification;
import com.odo.notification.domain.repository.NotificationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * NotificationRepository의 JPA 구현. 목록, 지우기, 비우기는 (recipient_account_id, seq) 유니크 인덱스로 읽는다.
 */
interface NotificationJpaRepository extends JpaRepository<Notification, Long>, NotificationRepository {

	@Override
	@Query("""
			select n from Notification n
			where n.recipientAccountId = :recipientAccountId and n.seq < :beforeSeq
			order by n.seq desc
			""")
	List<Notification> findPage(@Param("recipientAccountId") long recipientAccountId,
			@Param("beforeSeq") long beforeSeq, Limit limit);

	@Override
	@Query("""
			select n from Notification n
			where n.recipientAccountId = :recipientAccountId and n.seq > :afterSeq and n.seq <= :upToSeq
			order by n.seq
			""")
	List<Notification> findBetween(@Param("recipientAccountId") long recipientAccountId,
			@Param("afterSeq") long afterSeq, @Param("upToSeq") long upToSeq, Limit limit);

	@Override
	@Modifying
	@Query("delete from Notification n where n.id = :id and n.recipientAccountId = :recipientAccountId")
	int deleteMine(@Param("id") long id, @Param("recipientAccountId") long recipientAccountId);

	@Override
	@Modifying
	@Query("delete from Notification n where n.recipientAccountId = :recipientAccountId and n.seq <= :upTo")
	int deleteUpTo(@Param("recipientAccountId") long recipientAccountId, @Param("upTo") long upTo);

	@Override
	@Query(value = """
			SELECT EXISTS (
			    SELECT 1 FROM notification
			    WHERE recipient_account_id = :recipientAccountId
			      AND seq > COALESCE((SELECT seen_seq FROM notification_inbox WHERE account_id = :recipientAccountId), 0)
			)
			""", nativeQuery = true)
	boolean existsUnseen(@Param("recipientAccountId") long recipientAccountId);

}
