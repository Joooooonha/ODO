package com.odo.performance.presentation.dto;

import com.odo.performance.domain.entity.PerformanceRole;

/** 1차에는 DIRECTOR만 받는다. MEMBER나 값이 없으면 INVALID_ROLE이다. */
public record DesignateDirectorRequest(PerformanceRole role) {

}
