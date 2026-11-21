package com.odo.performance.presentation.dto;

import java.util.List;

/** description은 선택이다. members는 명단 전체이고 팀장이 반드시 들어 있어야 한다. */
public record CreatePerformanceRequest(String name, String description, List<MemberEntryRequest> members) {

}
