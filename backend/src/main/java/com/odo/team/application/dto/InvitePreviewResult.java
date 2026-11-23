package com.odo.team.application.dto;

/** 초대 확인. myRequestId는 myStatus가 PENDING일 때만 있다(취소에 쓴다). */
public record InvitePreviewResult(
		long teamId,
		String teamName,
		String leaderName,
		String description,
		InviteStatus myStatus,
		Long myRequestId) {

}
