package com.odo.schedule.domain.entity;

/**
 * 일정 참여자의 상태. 투표 없는 일정은 필수와 제외, 투표 일정은 필수와 선택만 쓴다.
 * 필수는 투표 없이 참여하고, 선택은 참여로 투표해야 참여 인원에 들어간다.
 */
public enum ParticipantStatus {

	REQUIRED, OPTIONAL, EXCLUDED

}
