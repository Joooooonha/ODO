package com.odo.notification.domain.repository;

import java.util.List;

import com.odo.notification.domain.entity.Notification;
import org.springframework.data.domain.Limit;

public interface NotificationRepository {

	<S extends Notification> List<S> saveAll(Iterable<S> notifications);

	/** 받는 사람의 알림 중 순번이 beforeSeq보다 작은 것, 순번이 큰 것부터. 첫 페이지는 beforeSeq에 Long.MAX_VALUE를 준다. */
	List<Notification> findPage(long recipientAccountId, long beforeSeq, Limit limit);

	/** 받는 사람의 알림 중 순번이 afterSeq보다 크고 upToSeq 이하인 것, 순번 순으로. 실시간 전달이 쓴다. */
	List<Notification> findBetween(long recipientAccountId, long afterSeq, long upToSeq, Limit limit);

	/** 내 알림 하나를 지운다. 없거나 남의 알림이면 0이다. */
	int deleteMine(long id, long recipientAccountId);

	/** 내 알림 중 순번이 upTo 이하인 것을 지운다. */
	int deleteUpTo(long recipientAccountId, long upTo);

	/** 알림함을 열 때 본 순번보다 큰 알림이 남아 있는지. 알림함 상태 행이 없으면 본 순번은 0이다. */
	boolean existsUnseen(long recipientAccountId);

}
