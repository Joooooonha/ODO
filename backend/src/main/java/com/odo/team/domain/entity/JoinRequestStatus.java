package com.odo.team.domain.entity;

/**
 * 입장 요청 상태. PENDING에서만 APPROVED, REJECTED, CANCELED 중 하나로 바뀐다.
 */
public enum JoinRequestStatus {

	/** 요청을 보냈고 팀장이 아직 처리하지 않았다. */
	PENDING,

	/** 팀장이 승인해서 팀원이 되었다. */
	APPROVED,

	/** 팀장이 거절했다. */
	REJECTED,

	/** 요청한 사람이 스스로 취소했다. */
	CANCELED

}
