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
 * 알림함의 알림 한 건. 받는 사람마다 한 행이다. 11_Notification_spec.md의 "데이터".
 * seq는 받는 사람의 순번이고, 한 사람 안에서 커밋 순서와 같다(NotificationWriter).
 * payload(JSON 문자열)에는 볼 때 읽을 수 없는 값만 남는다. 사건의 내용(바뀐 상태)과 삭제 알림의 제목이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification")
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recipient_account_id", nullable = false)
	private Long recipientAccountId;

	@Column(nullable = false)
	private Long seq;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(name = "actor_account_id", nullable = false)
	private Long actorAccountId;

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

	private Notification(long recipientAccountId, long seq, long teamId, long actorAccountId, String type,
			String targetType, long targetId, String payload, Instant createdAt) {
		this.recipientAccountId = recipientAccountId;
		this.seq = seq;
		this.teamId = teamId;
		this.actorAccountId = actorAccountId;
		this.type = type;
		this.targetType = targetType;
		this.targetId = targetId;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public static Notification createNotification(long recipientAccountId, long seq, long teamId,
			long actorAccountId, String type, String targetType, long targetId, String payloadJson, Instant now) {
		return new Notification(recipientAccountId, seq, teamId, actorAccountId, type, targetType, targetId,
				payloadJson, now);
	}

}
