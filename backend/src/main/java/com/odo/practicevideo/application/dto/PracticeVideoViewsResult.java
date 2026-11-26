package com.odo.practicevideo.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * 시청 현황. 08_PracticeVideo_spec.md의 "시청 현황". 대상자는 지금의 명단에서 올린 디렉터를 뺀 사람이다.
 *
 * @param viewed 먼저 본 순서
 * @param notViewed 명단 순서(디렉터 먼저, 들어온 순서)
 */
public record PracticeVideoViewsResult(int targetCount, int viewedCount, List<Viewed> viewed,
		List<NotViewed> notViewed) {

	/** @param generation 기수가 없는 팀원은 null */
	public record Viewed(long accountId, String name, Integer generation, Instant viewedAt) {
	}

	/** @param generation 기수가 없는 팀원은 null */
	public record NotViewed(long accountId, String name, Integer generation) {
	}

}
