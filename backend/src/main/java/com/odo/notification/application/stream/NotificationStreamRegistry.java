package com.odo.notification.application.stream;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 이 서버에 열린 실시간 연결 목록. 계정마다 연결이 여러 개일 수 있다(탭, 기기). 11_Notification_spec.md의 "서버 사이 전달".
 * 서버가 꺼질 때(SmartLifecycle.stop) 먼저 모든 연결을 닫는다. SSE 연결은 끝나지 않는 요청이라, 웹 서버가 요청이 끝나기를
 * 기다리며 꺼지면 시간 제한까지 꺼지지 못한다. 기본 단계(DEFAULT_PHASE)라 웹 서버보다 먼저 멈춘다.
 */
@Component
public class NotificationStreamRegistry implements SmartLifecycle {

	private final Map<Long, Set<NotificationStreamConnection>> connections = new ConcurrentHashMap<>();

	private volatile boolean running;

	void add(NotificationStreamConnection connection) {
		connections.computeIfAbsent(connection.accountId(), key -> ConcurrentHashMap.newKeySet()).add(connection);
	}

	void remove(NotificationStreamConnection connection) {
		connections.computeIfPresent(connection.accountId(), (key, set) -> {
			set.remove(connection);
			return set.isEmpty() ? null : set;
		});
	}

	/** 연결이 하나 이상 있는 계정. 감지기의 쿼리에 넣는다(한 계정은 한 번만). */
	public Set<Long> accountIds() {
		return Set.copyOf(connections.keySet());
	}

	List<NotificationStreamConnection> connectionsOf(long accountId) {
		Set<NotificationStreamConnection> found = connections.get(accountId);
		return found == null ? List.of() : List.copyOf(found);
	}

	List<NotificationStreamConnection> all() {
		return connections.values().stream().flatMap(Set::stream).toList();
	}

	public int connectionCount() {
		return connections.values().stream().mapToInt(Set::size).sum();
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		all().forEach(connection -> {
			connection.close();
			remove(connection);
		});
	}

	@Override
	public boolean isRunning() {
		return running;
	}

}
