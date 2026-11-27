package com.odo.notification.application;

/**
 * 알림 종류(type)와 가리키는 자료의 종류(target_type) 이름. 11_Notification_spec.md의 "이벤트와 알림".
 * type 이름은 기능 이름을 앞에 붙인다(기능_사건).
 */
public final class NotificationTypes {

	public static final String SCHEDULE_CREATED = "SCHEDULE_CREATED";

	public static final String SCHEDULE_UPDATED = "SCHEDULE_UPDATED";

	public static final String SCHEDULE_DELETED = "SCHEDULE_DELETED";

	public static final String SCHEDULE_PARTICIPANT_STATUS_CHANGED = "SCHEDULE_PARTICIPANT_STATUS_CHANGED";

	public static final String TEAM_JOIN_REQUESTED = "TEAM_JOIN_REQUESTED";

	public static final String TEAM_JOIN_APPROVED = "TEAM_JOIN_APPROVED";

	public static final String TEAM_JOIN_REJECTED = "TEAM_JOIN_REJECTED";

	public static final String PERFORMANCE_MEMBER_ADDED = "PERFORMANCE_MEMBER_ADDED";

	public static final String PERFORMANCE_DIRECTOR_ASSIGNED = "PERFORMANCE_DIRECTOR_ASSIGNED";

	public static final String TARGET_SCHEDULE = "SCHEDULE";

	public static final String TARGET_JOIN_REQUEST = "JOIN_REQUEST";

	public static final String TARGET_TEAM = "TEAM";

	public static final String TARGET_PERFORMANCE = "PERFORMANCE";

	private NotificationTypes() {
	}

}
