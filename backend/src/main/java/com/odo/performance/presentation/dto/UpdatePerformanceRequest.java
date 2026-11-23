package com.odo.performance.presentation.dto;

/** 이름과 설명을 모두 보낸다(PUT). description이 없거나 null이거나 빈 문자열이면 설명을 비운다. */
public record UpdatePerformanceRequest(
		String name,
		String description) {

}
