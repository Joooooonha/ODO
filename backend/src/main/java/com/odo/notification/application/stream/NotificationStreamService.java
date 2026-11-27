package com.odo.notification.application.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.odo.notification.application.NotificationProperties;
import com.odo.notification.domain.exception.InvalidNotificationSeqException;
import com.odo.notification.domain.repository.NotificationInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 연결을 연다. 11_Notification_spec.md의 "연결: SSE"의 "연결을 열 때".
 * 따라잡기를 따로 하지 않는다. 보낸 순번만 정해 등록하면 감지기의 다음 차례에 그 뒤의 알림이 나간다.
 * 따라잡기와 실시간 전달이 한 길이라, 둘 사이에 온 알림이 빠지거나 두 번 가는 틈이 없다.
 */
@Service
@RequiredArgsConstructor
public class NotificationStreamService {

	private final NotificationStreamRegistry registry;

	private final NotificationInboxRepository inboxRepository;

	private final NotificationProperties properties;

	private final Clock clock;

	/**
	 * lastEventId는 화면이 마지막으로 받은 순번이다. 없으면 지금의 마지막 순번부터(연결한 뒤의 알림만) 보낸다.
	 * 마지막 순번보다 크면 마지막 순번으로 낮춘다. 음수 InvalidNotificationSeqException.
	 * 연결의 시간 제한은 접근 토큰의 만료 시각까지다. 만료되면 서버가 연결을 닫고, 화면이 갱신한 뒤 다시 연결한다.
	 */
	public SseEmitter open(long accountId, Long lastEventId, Instant tokenExpiresAt) {
		if (lastEventId != null && lastEventId < 0) {
			throw new InvalidNotificationSeqException();
		}
		long lastSeq = inboxRepository.findLastSeq(accountId);
		long sentSeq = lastEventId == null ? lastSeq : Math.min(lastEventId, lastSeq);
		long timeoutMillis = Math.max(1, Duration.between(clock.instant(), tokenExpiresAt).toMillis());
		SseEmitter emitter = new SseEmitter(timeoutMillis);
		NotificationStreamConnection connection = new NotificationStreamConnection(accountId, emitter, sentSeq);
		emitter.onTimeout(connection::close);
		emitter.onCompletion(() -> registry.remove(connection));
		emitter.onError(ex -> registry.remove(connection));
		registry.add(connection);
		connection.send(SseEmitter.event().reconnectTime(properties.reconnectRetry().toMillis()));
		return emitter;
	}

}
