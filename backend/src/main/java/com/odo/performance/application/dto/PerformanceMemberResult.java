package com.odo.performance.application.dto;

import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.entity.PerformanceRole;

/** 명단의 한 항목. generation은 팀원 정보의 지금 기수다. 명단 추가와 디렉터 지정의 결과도 이 형식이다. */
public record PerformanceMemberResult(
		long accountId,
		String name,
		Integer generation,
		PerformanceRole role) {

	public static PerformanceMemberResult of(PerformanceMember member, String name, Integer generation) {
		return new PerformanceMemberResult(member.getAccountId(), name, generation, member.getRole());
	}

}
