package com.odo.notification.application;

import java.util.List;

import com.odo.notification.application.dto.NotificationPageResult;
import com.odo.notification.application.dto.NotificationResult;
import com.odo.notification.domain.entity.Notification;
import com.odo.notification.domain.exception.InvalidNotificationCursorException;
import com.odo.notification.domain.exception.InvalidNotificationSeqException;
import com.odo.notification.domain.exception.NotificationNotFoundException;
import com.odo.notification.domain.repository.NotificationInboxRepository;
import com.odo.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 알림함의 목록, 하나 지우기, 비우기, 새 알림 표시, 열기. 11_Notification_spec.md의 API 명세.
 * 모두 로그인한 사람 자신의 알림만 다룬다. 비우기와 열기는 화면이 본 맨 위 순번을 기준으로 한다
 * (설계의 "화면이 본 순번으로 열기와 비우기").
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notificationRepository;

	private final NotificationInboxRepository inboxRepository;

	private final NotificationViewAssembler viewAssembler;

	private final NotificationProperties properties;

	/** 최신순 한 페이지. cursor는 앞 페이지의 nextCursor이고, 그 순번보다 작은 알림부터 온다. 1보다 작으면 InvalidNotificationCursorException. */
	@Transactional(readOnly = true)
	public NotificationPageResult findPage(long accountId, Long cursor) {
		if (cursor != null && cursor < 1) {
			throw new InvalidNotificationCursorException();
		}
		int pageSize = properties.pageSize();
		List<Notification> found = notificationRepository.findPage(accountId,
				cursor == null ? Long.MAX_VALUE : cursor, Limit.of(pageSize + 1));
		List<Notification> page = found.size() > pageSize ? found.subList(0, pageSize) : found;
		Long nextCursor = found.size() > pageSize ? page.getLast().getSeq() : null;
		List<NotificationResult> items = viewAssembler.assemble(page);
		return new NotificationPageResult(items, nextCursor);
	}

	/** 없거나 남의 알림 NotificationNotFoundException. */
	@Transactional
	public void delete(long accountId, long notificationId) {
		if (notificationRepository.deleteMine(notificationId, accountId) == 0) {
			throw new NotificationNotFoundException();
		}
	}

	/** upTo 이하의 내 알림을 모든 팀 스페이스에서 지운다. 지울 것이 없어도 성공이다. 음수 InvalidNotificationSeqException. */
	@Transactional
	public void clear(long accountId, long upTo) {
		requireNotNegative(upTo);
		notificationRepository.deleteUpTo(accountId, upTo);
	}

	@Transactional(readOnly = true)
	public boolean hasNew(long accountId) {
		return notificationRepository.existsUnseen(accountId);
	}

	/** 화면이 본 맨 위 순번을 본 순번으로 둔다. 줄지 않고, 매긴 순번보다 크면 매긴 순번으로 낮춘다. 음수 InvalidNotificationSeqException. */
	@Transactional
	public void markOpened(long accountId, long seq) {
		requireNotNegative(seq);
		inboxRepository.markSeen(accountId, seq);
	}

	private static void requireNotNegative(long seq) {
		if (seq < 0) {
			throw new InvalidNotificationSeqException();
		}
	}

}
