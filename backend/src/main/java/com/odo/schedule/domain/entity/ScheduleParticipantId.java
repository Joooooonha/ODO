package com.odo.schedule.domain.entity;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 참여자 행의 기본 키 (schedule_id, account_id). 한 일정에 한 사람은 한 행이다. JPA의 @IdClass로 쓴다. */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ScheduleParticipantId implements Serializable {

	private Long scheduleId;

	private Long accountId;

}
