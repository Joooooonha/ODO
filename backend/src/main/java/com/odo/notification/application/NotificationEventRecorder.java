package com.odo.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import com.odo.notification.domain.entity.NotificationEvent;
import com.odo.notification.domain.repository.NotificationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 이벤트를 기록한다(발행). 알림 기능이 이 기록을 읽어 내보낸다(구독). 11_Notification_spec.md의 "알림 이벤트 기록".
 * 알림이 생기는 변경과 같은 트랜잭션에서만 부른다. 트랜잭션 밖에서 부르면 IllegalTransactionStateException이다.
 * 변경은 됐는데 알림이 빠지거나, 롤백된 변경의 알림이 남는 일을 막기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class NotificationEventRecorder {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final NotificationEventRepository notificationEventRepository;

	private final Clock clock;

	/**
	 * 받는 사람마다 한 행을 쓴다. 같은 사람이 여러 번 있어도 한 행이다.
	 * 변경한 사람(actorAccountId)에게는 쓰지 않는다. 자기가 한 일이기 때문이다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(NotificationEventDraft draft, Collection<Long> recipientAccountIds, long actorAccountId) {
		Set<Long> recipients = new LinkedHashSet<>(recipientAccountIds);
		recipients.remove(actorAccountId);
		if (recipients.isEmpty()) {
			return;
		}
		String payload = JSON.writeValueAsString(draft.payload());
		Instant now = clock.instant();
		for (long recipient : recipients) {
			notificationEventRepository.save(NotificationEvent.createEvent(recipient, draft.teamId(), draft.type(),
					draft.targetType(), draft.targetId(), payload, now));
		}
	}

}
