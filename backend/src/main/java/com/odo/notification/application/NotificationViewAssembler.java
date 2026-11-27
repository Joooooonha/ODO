package com.odo.notification.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.notification.application.dto.NotificationResult;
import com.odo.notification.domain.entity.Notification;
import com.odo.performance.application.PerformanceReader;
import com.odo.schedule.application.ScheduleReader;
import com.odo.team.application.TeamReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.odo.notification.application.NotificationTypes.SCHEDULE_DELETED;
import static com.odo.notification.application.NotificationTypes.SCHEDULE_PARTICIPANT_STATUS_CHANGED;
import static com.odo.notification.application.NotificationTypes.TARGET_JOIN_REQUEST;
import static com.odo.notification.application.NotificationTypes.TARGET_PERFORMANCE;
import static com.odo.notification.application.NotificationTypes.TARGET_SCHEDULE;
import static com.odo.notification.application.NotificationTypes.TARGET_TEAM;

/**
 * 알림 행에 볼 때 읽는 이름을 채운다. 11_Notification_spec.md의 "보여 줄 값: 볼 때 읽기".
 * 한 묶음의 팀, 공연, 일정, 한 사람 ID를 모아 종류마다 한 번에 읽는다. 알림이 기능의 테이블을 직접 읽지 않는다.
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class NotificationViewAssembler {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TeamReader teamReader;

	private final PerformanceReader performanceReader;

	private final ScheduleReader scheduleReader;

	private final AccountReader accountReader;

	public List<NotificationResult> assemble(List<Notification> notifications) {
		if (notifications.isEmpty()) {
			return List.of();
		}
		Map<Long, String> teamNames = teamReader
			.namesOf(notifications.stream().map(Notification::getTeamId).collect(Collectors.toSet()));
		Map<Long, String> actorNames = accountReader
			.namesOf(notifications.stream().map(Notification::getActorAccountId).collect(Collectors.toSet()));
		Map<Long, String> performanceNames = performanceReader.namesOf(targetIds(notifications, TARGET_PERFORMANCE));
		Map<Long, String> scheduleTitles = scheduleReader.titlesOf(targetIds(notifications, TARGET_SCHEDULE));
		return notifications.stream().map(notification -> {
			Map<String, Object> payload = payloadOf(notification);
			Target target = targetOf(notification, payload, teamNames, performanceNames, scheduleTitles);
			return new NotificationResult(notification.getId(), notification.getSeq(), notification.getType(),
					notification.getTeamId(), teamNames.get(notification.getTeamId()),
					notification.getActorAccountId(), actorNames.get(notification.getActorAccountId()),
					notification.getTargetType(), notification.getTargetId(), target.name(), target.deleted(),
					detailOf(notification, payload), notification.getCreatedAt());
		}).toList();
	}

	private static Set<Long> targetIds(List<Notification> notifications, String targetType) {
		return notifications.stream()
			.filter(notification -> notification.getTargetType().equals(targetType))
			.map(Notification::getTargetId)
			.collect(Collectors.toSet());
	}

	/**
	 * 삭제 알림은 지울 때의 제목이고 늘 deleted다. 그 밖의 대상은 지금 이름이고, 없으면 이름 없이 deleted다.
	 * 입장 요청은 이름이 없고, 취소하거나 처리해도 행이 남아 deleted가 아니다.
	 */
	private static Target targetOf(Notification notification, Map<String, Object> payload, Map<Long, String> teamNames,
			Map<Long, String> performanceNames, Map<Long, String> scheduleTitles) {
		if (notification.getType().equals(SCHEDULE_DELETED)) {
			return new Target((String) payload.get("title"), true);
		}
		return switch (notification.getTargetType()) {
			case TARGET_SCHEDULE -> existing(scheduleTitles.get(notification.getTargetId()));
			case TARGET_PERFORMANCE -> existing(performanceNames.get(notification.getTargetId()));
			case TARGET_TEAM -> existing(teamNames.get(notification.getTargetId()));
			case TARGET_JOIN_REQUEST -> new Target(null, false);
			default -> throw new IllegalStateException("모르는 대상 종류: " + notification.getTargetType());
		};
	}

	private static Target existing(String name) {
		return new Target(name, name == null);
	}

	/** 참여 상태 변경 알림만 그때의 상태를 내용으로 보여 준다. */
	private static Map<String, Object> detailOf(Notification notification, Map<String, Object> payload) {
		if (notification.getType().equals(SCHEDULE_PARTICIPANT_STATUS_CHANGED)) {
			return Map.of("status", payload.get("status"));
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> payloadOf(Notification notification) {
		return JSON.readValue(notification.getPayload(), Map.class);
	}

	private record Target(String name, boolean deleted) {

	}

}
