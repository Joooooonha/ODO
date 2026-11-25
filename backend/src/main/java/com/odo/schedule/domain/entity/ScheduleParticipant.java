package com.odo.schedule.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 일정 참여자 한 사람. 일정마다 팀원 전원의 행이 있다. 03_Schedule_spec.md의 "참여자 행과 새 팀원".
 * 투표(vote)는 선택 참여자만 가진다. 미투표는 null이다.
 * 기본 키를 직접 정하므로 Persistable로 새 행인지 알린다. 그러지 않으면 Spring Data가 저장할 때마다 먼저 SELECT한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(ScheduleParticipantId.class)
@Table(name = "schedule_participant")
public class ScheduleParticipant implements Persistable<ScheduleParticipantId> {

	@Id
	@Column(name = "schedule_id")
	private Long scheduleId;

	@Id
	@Column(name = "account_id")
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ParticipantStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 3)
	private Vote vote;

	@Column(name = "voted_at")
	private Instant votedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Transient
	private boolean newRow = true;

	public static ScheduleParticipant createParticipant(long scheduleId, long accountId, ParticipantStatus status,
			Instant now) {
		ScheduleParticipant participant = new ScheduleParticipant();
		participant.scheduleId = scheduleId;
		participant.accountId = accountId;
		participant.status = status;
		participant.createdAt = now;
		participant.updatedAt = now;
		return participant;
	}

	/**
	 * 팀장이 상태를 바꾼다. 같은 상태면 아무것도 바꾸지 않고 false를 돌려준다.
	 * 선택이 아닌 상태가 되면 투표를 지운다. 필수에서 선택으로 바뀌면 미투표로 시작한다(예전 투표는 되살리지 않는다).
	 * 상태가 그 일정에서 쓸 수 있는지는 Schedule.requireAllowedStatus가 먼저 확인한다.
	 */
	public boolean changeStatus(ParticipantStatus newStatus, Instant now) {
		if (newStatus == status) {
			return false;
		}
		this.status = newStatus;
		this.vote = null;
		this.votedAt = null;
		this.updatedAt = now;
		return true;
	}

	public boolean isOptional() {
		return status == ParticipantStatus.OPTIONAL;
	}

	@Override
	public ScheduleParticipantId getId() {
		return new ScheduleParticipantId(scheduleId, accountId);
	}

	@Override
	public boolean isNew() {
		return newRow;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.newRow = false;
	}

}
