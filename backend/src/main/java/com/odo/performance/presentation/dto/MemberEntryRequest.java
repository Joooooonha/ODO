package com.odo.performance.presentation.dto;

import java.util.List;

import com.odo.common.error.InvalidRequestException;
import com.odo.performance.application.dto.MemberAssignment;
import com.odo.performance.domain.entity.PerformanceRole;

/**
 * 명단에 넣을 한 사람. role이 DIRECTOR, MEMBER가 아니면 JSON을 읽을 때 실패해 INVALID_REQUEST가 된다.
 * accountId나 role이 없어도 INVALID_REQUEST다.
 */
public record MemberEntryRequest(Long accountId, PerformanceRole role) {

	/** 명단이 없으면 빈 목록으로 본다. 빈 명단을 어떤 오류로 볼지는 서비스가 정한다(공연 만들기와 명단 추가가 다르다). */
	public static List<MemberAssignment> toAssignments(List<MemberEntryRequest> entries) {
		if (entries == null) {
			return List.of();
		}
		return entries.stream().map(MemberEntryRequest::toAssignment).toList();
	}

	private static MemberAssignment toAssignment(MemberEntryRequest entry) {
		if (entry == null || entry.accountId() == null || entry.role() == null) {
			throw new InvalidRequestException();
		}
		return new MemberAssignment(entry.accountId(), entry.role());
	}

}
