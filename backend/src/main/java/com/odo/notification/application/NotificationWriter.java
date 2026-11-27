package com.odo.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.odo.notification.domain.entity.Notification;
import com.odo.notification.domain.repository.NotificationInboxRepository;
import com.odo.notification.domain.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림을 받는 사람마다 순번을 매겨 저장한다. 11_Notification_spec.md의 "트랜잭션 안에서 기록하는 순서"와 "순번".
 * add는 바로 저장하지 않고 트랜잭션마다 하나인 버퍼에 쌓는다. 커밋 직전(beforeCommit)에 받는 사람 전체를 계정 ID 순으로 정렬해
 * 한 번에 순번을 올리고(알림함 행 잠금) 알림 행을 넣는다.
 * 근거: 알림함 잠금을 트랜잭션의 맨 마지막에, 정렬한 순서로 잡아 다른 잠금이나 다른 알림 트랜잭션과 교착이 생기지 않게 한다.
 * 변경과 같은 트랜잭션에서만 부른다. 트랜잭션 밖이면 IllegalStateException이다.
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final NotificationRepository notificationRepository;

	private final NotificationInboxRepository inboxRepository;

	private final EntityManager entityManager;

	private final Clock clock;

	public void add(NotificationDraft draft) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("알림은 변경과 같은 트랜잭션 안에서만 기록한다");
		}
		Buffer buffer = (Buffer) TransactionSynchronizationManager.getResource(this);
		if (buffer == null) {
			buffer = new Buffer();
			TransactionSynchronizationManager.bindResource(this, buffer);
			TransactionSynchronizationManager.registerSynchronization(buffer);
		}
		buffer.drafts.add(draft);
	}

	/** 버퍼에 쌓인 초안을 저장한다. 앞선 엔티티 변경을 먼저 반영(flush)해, 알림함 잠금이 트랜잭션의 마지막 잠금이 되게 한다. */
	private void save(List<NotificationDraft> drafts) {
		entityManager.flush();
		// 받는 사람마다(계정 ID 순) 받을 초안을 더한 순서대로 모은다.
		Map<Long, List<NotificationDraft>> byRecipient = new TreeMap<>();
		for (NotificationDraft draft : drafts) {
			Set<Long> recipients = new LinkedHashSet<>(draft.recipientAccountIds());
			recipients.remove(draft.actorAccountId());
			for (long recipient : recipients) {
				byRecipient.computeIfAbsent(recipient, key -> new ArrayList<>()).add(draft);
			}
		}
		if (byRecipient.isEmpty()) {
			return;
		}
		Instant now = clock.instant();
		List<Notification> notifications = new ArrayList<>();
		for (Map.Entry<Long, List<NotificationDraft>> entry : byRecipient.entrySet()) {
			long recipient = entry.getKey();
			List<NotificationDraft> received = entry.getValue();
			long lastSeq = inboxRepository.increaseLastSeq(recipient, received.size());
			long seq = lastSeq - received.size() + 1;
			for (NotificationDraft draft : received) {
				notifications.add(Notification.createNotification(recipient, seq++, draft.teamId(),
						draft.actorAccountId(), draft.type(), draft.targetType(), draft.targetId(),
						JSON.writeValueAsString(draft.payload()), now));
			}
		}
		notificationRepository.saveAll(notifications);
		entityManager.flush();
	}

	/** 트랜잭션 하나의 초안 묶음. 커밋 직전에 저장하고, 끝나면 트랜잭션에서 푼다. */
	private class Buffer implements TransactionSynchronization {

		private final List<NotificationDraft> drafts = new ArrayList<>();

		@Override
		public void beforeCommit(boolean readOnly) {
			save(drafts);
		}

		@Override
		public void afterCompletion(int status) {
			TransactionSynchronizationManager.unbindResourceIfPossible(NotificationWriter.this);
		}

	}

}
