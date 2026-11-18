package com.odo.team.application.dto;

/** 초대 링크를 연 사람의 상태. */
public enum InviteStatus {

	/** 요청할 수 있다. */
	NONE,
	/** 대기 중인 요청이 있다. */
	PENDING,
	/** 이미 팀원이다. */
	MEMBER

}
