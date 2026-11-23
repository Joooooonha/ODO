package com.odo.performance.domain.entity;

/** 공연 안에서의 역할. 팀장인지는 여기에 적지 않고 팀원의 역할(TeamRole)로 본다. */
public enum PerformanceRole {

	/** 곡을 만들 수 있다. 팀장이 명단에 넣거나 지정한다. */
	DIRECTOR,

	/** 공연에 참여하는 팀원. */
	MEMBER

}
