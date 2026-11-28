package com.odo.notification.infrastructure;

import java.util.Set;

import com.odo.notification.application.NotificationProperties;
import com.odo.notification.application.stream.NotificationStreamDispatcher;
import com.odo.notification.application.stream.NotificationStreamRegistry;
import com.odo.notification.domain.repository.NotificationInboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * 새 알림 감지(1차: DB 폴링). 11_Notification_spec.md의 "서버 사이 전달: DB 폴링".
 * poll-interval(0.5초)마다 이 서버에 연결된 계정들의 마지막 순번을 쿼리 한 번으로 읽어 전달기에 넘긴다. 연결이 없으면 쿼리를 보내지 않는다.
 * 매번 DB의 순번을 직접 보므로 신호가 빠지는 경우가 없다. 알림을 만든 서버가 어디든 같다.
 * 감지 방식을 바꿀 때(Redis, LISTEN/NOTIFY)는 이 클래스만 바꾸고, 전달기(NotificationStreamDispatcher)에 마지막 순번을 넘기면 된다.
 * 테스트는 끄고(odo.notification.detector-enabled=false), 실시간 통합 테스트만 켠다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "odo.notification.detector-enabled", havingValue = "true", matchIfMissing = true)
public class PollingInboxChangeDetector implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(PollingInboxChangeDetector.class);

	private final NotificationStreamRegistry registry;

	private final NotificationInboxRepository inboxRepository;

	private final NotificationStreamDispatcher dispatcher;

	private final NotificationProperties properties;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(this::poll, properties.pollInterval());
	}

	void poll() {
		try {
			Set<Long> accountIds = registry.accountIds();
			if (!accountIds.isEmpty()) {
				dispatcher.dispatch(inboxRepository.findLastSeqs(accountIds));
			}
			dispatcher.sendHeartbeats();
		}
		catch (RuntimeException ex) {
			// DB가 잠깐 닿지 않아도 다음 차례에 다시 본다. 순번은 DB에 남아 있어 빠지지 않는다.
			log.warn("알림 감지에 실패했다", ex);
		}
	}

}
