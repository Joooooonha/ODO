package com.odo.notification.application.stream;

import java.util.List;
import java.util.Map;

import com.odo.notification.application.NotificationProperties;
import com.odo.notification.application.NotificationViewAssembler;
import com.odo.notification.application.dto.NotificationResult;
import com.odo.notification.domain.entity.Notification;
import com.odo.notification.domain.repository.NotificationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 순번이 늘어난 계정의 새 알림을 읽어 그 계정의 연결로 보낸다. 11_Notification_spec.md의 "서버 사이 전달"과 "연결: SSE".
 * 감지기는 "이 계정의 마지막 순번은 몇이다"만 알려 주고, 읽고 보내는 일은 여기서 한다. 감지 방식을 바꿔도 이 클래스는 그대로다.
 * 감지기의 스레드에서 돈다.
 */
@Component
public class NotificationStreamDispatcher {

	private final NotificationStreamRegistry registry;

	private final NotificationRepository notificationRepository;

	private final NotificationViewAssembler viewAssembler;

	private final NotificationStreamDataMapper dataMapper;

	private final NotificationProperties properties;

	private final TransactionTemplate readOnly;

	public NotificationStreamDispatcher(NotificationStreamRegistry registry,
			NotificationRepository notificationRepository, NotificationViewAssembler viewAssembler,
			NotificationStreamDataMapper dataMapper, NotificationProperties properties,
			PlatformTransactionManager transactionManager) {
		this.registry = registry;
		this.notificationRepository = notificationRepository;
		this.viewAssembler = viewAssembler;
		this.dataMapper = dataMapper;
		this.properties = properties;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
	}

	/** 감지기가 읽은 계정별 마지막 순번. 연결에 보낸 순번보다 큰 계정만 보낸다. */
	public void dispatch(Map<Long, Long> lastSeqs) {
		lastSeqs.forEach(this::dispatch);
	}

	/**
	 * 밀린 연결들 중 가장 밀린 연결부터 알림을 상한 + 1건까지 읽는다. 상한 안이면 그 결과로 모든 연결에 보낸다(흔한 경우, 쿼리 한 번).
	 * 상한을 넘으면 연결마다 자기 보낸 순번부터 다시 읽어, 그 연결 기준으로도 넘는 연결에만 reset을 보낸다.
	 * 상한은 순번 차이가 아니라 실제로 남은 알림 수로 잰다. 순번은 줄지 않고 알림은 지우면 사라지므로, 순번 차이로 재면
	 * 알림함을 비운 사람이 연결할 때마다 reset을 받는다(PR #17 리뷰).
	 */
	private void dispatch(long accountId, long lastSeq) {
		List<NotificationStreamConnection> behind = registry.connectionsOf(accountId)
			.stream()
			.filter(connection -> connection.sentSeq() < lastSeq)
			.toList();
		if (behind.isEmpty()) {
			return;
		}
		long from = behind.stream().mapToLong(NotificationStreamConnection::sentSeq).min().orElseThrow();
		List<NotificationResult> shared = readWithinLimit(accountId, from, lastSeq);
		for (NotificationStreamConnection connection : behind) {
			List<NotificationResult> results = shared != null ? shared
					: readWithinLimit(accountId, connection.sentSeq(), lastSeq);
			if (results == null) {
				// 너무 밀린 연결은 알림 대신 reset을 보내고 닫는다. 화면이 목록 API로 처음부터 다시 읽는다.
				connection.send(SseEmitter.event().name("reset").data(Map.of(), MediaType.APPLICATION_JSON));
				closeAndRemove(connection);
				continue;
			}
			if (send(connection, results)) {
				connection.advanceTo(lastSeq);
			}
		}
	}

	/**
	 * afterSeq 뒤부터 lastSeq까지의 알림을 상한 + 1건까지 읽어 채운다. 상한보다 많으면 null(reset 대상)이다.
	 * lastSeq까지는 이미 커밋되었다(순번과 알림 행이 같은 트랜잭션). 그사이 지운 알림은 읽히지 않는다.
	 */
	private List<NotificationResult> readWithinLimit(long accountId, long afterSeq, long lastSeq) {
		int limit = properties.catchUpLimit();
		return readOnly.execute(status -> {
			List<Notification> found = notificationRepository.findBetween(accountId, afterSeq, lastSeq,
					Limit.of(limit + 1));
			return found.size() > limit ? null : viewAssembler.assemble(found);
		});
	}

	/** 그 연결의 보낸 순번보다 큰 알림을 순번 순으로 보낸다. 보내다 실패하면 연결을 빼고 false다. */
	private boolean send(NotificationStreamConnection connection, List<NotificationResult> results) {
		long sentSeq = connection.sentSeq();
		for (NotificationResult result : results) {
			if (result.seq() <= sentSeq) {
				continue;
			}
			boolean sent = connection.send(SseEmitter.event()
				.id(String.valueOf(result.seq()))
				.name("notification")
				.data(dataMapper.toData(result), MediaType.APPLICATION_JSON));
			if (!sent) {
				registry.remove(connection);
				return false;
			}
			connection.advanceTo(result.seq());
		}
		return true;
	}

	/** 마지막으로 보낸 뒤 heartbeat-interval이 지난 연결에 빈 신호(SSE 주석)를 보낸다. 끊긴 연결은 여기서 드러나 빠진다. */
	public void sendHeartbeats() {
		long interval = properties.heartbeatInterval().toNanos();
		for (NotificationStreamConnection connection : registry.all()) {
			if (connection.idleNanos() >= interval && !connection.send(SseEmitter.event().comment(""))) {
				registry.remove(connection);
			}
		}
	}

	private void closeAndRemove(NotificationStreamConnection connection) {
		connection.close();
		registry.remove(connection);
	}

}
