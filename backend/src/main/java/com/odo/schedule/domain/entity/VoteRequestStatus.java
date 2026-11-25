package com.odo.schedule.domain.entity;

/** 투표 요청의 처리 결과. 03_Schedule_spec.md의 "투표 결과". */
public enum VoteRequestStatus {

	/** 아직 처리하지 않았다. 도착 시각 + W가 지나면 작업자가 처리한다. */
	PENDING,

	/** 반영했다. 참여면 참여 확정, 불참이면 불참이다. */
	CONFIRMED,

	/** 정원이 차서 반영하지 않았다. 투표는 바뀌지 않는다. */
	FULL,

	/** 반영할 수 없는 요청이다. 이유는 VoteInvalidReason이다. */
	INVALID

}
