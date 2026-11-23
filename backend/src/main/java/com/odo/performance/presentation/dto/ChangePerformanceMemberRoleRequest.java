package com.odo.performance.presentation.dto;

import com.odo.performance.domain.entity.PerformanceRole;

/**
 * 명단 항목의 역할을 바꾼다. 바꿀 역할을 명시해서 받는다.
 * 1차에는 DIRECTOR(디렉터 지정)만 받는다. MEMBER나 값이 없으면 INVALID_ROLE이다.
 */
public record ChangePerformanceMemberRoleRequest(PerformanceRole role) {

}
