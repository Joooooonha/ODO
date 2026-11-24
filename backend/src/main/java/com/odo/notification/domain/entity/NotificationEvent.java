package com.odo.notification.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * 알림 이벤트 한 건. 받는 사람마다 한 행이다. 11_Notification_spec.md의 "알림 이벤트 기록".
 * payload는 화면에 보여 줄 값의 복사본(JSON 문자열)이다. 가리키던 자료가 지워져도 알림을 보여 줄 수 있게 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_event")
public class NotificationEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recipient_account_id", nullable = false)
	private Long recipientAccountId;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(nullable = false, length = 50)
	private String type;

	@Column(name = "target_type", nullable = false, length = 30)
	private String targetType;

	@Column(name = "target_id", nullable = false)
	private Long targetId;

	/** 문자열로 들고 저장할 때 jsonb로 바꾼다. */
	@Column(nullable = false, columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private NotificationEvent(long recipientAccountId, long teamId, String type, String targetType, long targetId,
			String payload, Instant createdAt) {
		this.recipientAccountId = recipientAccountId;
		this.teamId = teamId;
		this.type = type;
		this.targetType = targetType;
		this.targetId = targetId;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public static NotificationEvent createEvent(long recipientAccountId, long teamId, String type, String targetType,
			long targetId, String payloadJson, Instant now) {
		return new NotificationEvent(recipientAccountId, teamId, type, targetType, targetId, payloadJson, now);
	}

}
