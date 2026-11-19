package com.odo.team.domain.entity;

/** 팀 안에서의 역할. */
public enum TeamRole {

	/** 팀을 만든 사람. 팀마다 한 명이고, 초대 코드 관리와 입장 요청 승인, 거절을 할 수 있다. */
	LEADER,

	/** 입장 요청이 승인되어 들어온 팀원. */
	MEMBER

}
