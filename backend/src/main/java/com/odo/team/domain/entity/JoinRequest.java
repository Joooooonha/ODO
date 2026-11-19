package com.odo.team.domain.entity;

import java.time.Instant;

import com.odo.team.domain.rule.Generation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 입장 요청. 02_TeamSpace_spec.md의 "입장 요청 상태".
 * 상태를 바꾼 행은 지우지 않고, 다시 요청하면 새 행을 만든다.
 * 한 사람이 한 팀에 PENDING을 하나만 갖는 것은 DB의 부분 유니크 인덱스가 지킨다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "join_request")
public class JoinRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	/** 요청할 때 적은 기수. 승인되면 이 값으로 팀원이 된다. */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer generation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private JoinRequestStatus status;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	/** 승인, 거절, 취소된 시각. 대기 중이면 null. */
	@Column(name = "decided_at")
	private Instant decidedAt;

	private JoinRequest(long teamId, long accountId, Integer generation, Instant now) {
		this.teamId = teamId;
		this.accountId = accountId;
		this.generation = generation;
		this.status = JoinRequestStatus.PENDING;
		this.requestedAt = now;
	}

	/** 대기 중인 새 요청. 기수가 규칙에 맞지 않으면 InvalidGenerationException. */
	public static JoinRequest pending(long teamId, long accountId, Integer generation, Instant now) {
		Generation.validate(generation);
		return new JoinRequest(teamId, accountId, generation, now);
	}

}
