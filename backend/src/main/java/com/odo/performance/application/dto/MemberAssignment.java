package com.odo.performance.application.dto;

import com.odo.performance.domain.entity.PerformanceRole;

/** 명단에 넣을 사람과 그 사람의 역할. 공연 만들기와 명단에 추가의 입력이다. */
public record MemberAssignment(long accountId, PerformanceRole role) {

}
