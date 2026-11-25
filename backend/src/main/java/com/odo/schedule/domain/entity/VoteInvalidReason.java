package com.odo.schedule.domain.entity;

/** 투표 요청이 무효인 이유. 작업자가 판정 표의 순서대로 확인한다(03_Schedule_spec.md의 "처리 (작업자)"). */
public enum VoteInvalidReason {

	/** 일정이 없거나 지워졌다. 요청한 사람이 그 일정의 참여자가 아닐 때(팀원 아님)도 일정이 있다는 것을 드러내지 않도록 이 이유다. */
	SCHEDULE_NOT_FOUND,

	/** 투표를 받지 않는 일정이다. */
	NOT_VOTING_SCHEDULE,

	/** 도착 시각이 투표 마감 기한과 같거나 그 뒤다. */
	VOTING_CLOSED,

	/** 요청한 사람이 선택 참여자가 아니다(필수). */
	NOT_OPTIONAL_PARTICIPANT

}
