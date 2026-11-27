package com.odo.notification.domain.repository;

/**
 * 계정마다 하나인 알림함 상태 행(notification_inbox). 11_Notification_spec.md의 "순번".
 */
public interface NotificationInboxRepository {

	/**
	 * 그 사람의 마지막 순번을 count만큼 올리고 올린 값을 돌려준다. 행이 없으면 만든다.
	 * 그 사람의 행을 트랜잭션이 끝날 때까지 잠근다. 같은 사람에게 알림을 넣는 다른 트랜잭션은 이 커밋을 기다린다.
	 */
	long increaseLastSeq(long accountId, int count);

	/** 본 순번을 seq로 둔다. 줄이지 않고, 마지막 순번보다 크면 마지막 순번으로 낮춘다. 행이 없으면 아무것도 하지 않는다. */
	void markSeen(long accountId, long seq);

}
