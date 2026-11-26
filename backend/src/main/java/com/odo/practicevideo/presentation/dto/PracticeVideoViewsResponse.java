package com.odo.practicevideo.presentation.dto;

import java.time.Instant;
import java.util.List;

import com.odo.practicevideo.application.dto.PracticeVideoViewsResult;

/** 시청 현황. 08_PracticeVideo_spec.md의 "시청 현황". */
public record PracticeVideoViewsResponse(int targetCount, int viewedCount, List<Viewed> viewed,
		List<NotViewed> notViewed) {

	public record Viewed(long accountId, String name, Integer generation, Instant viewedAt) {
	}

	public record NotViewed(long accountId, String name, Integer generation) {
	}

	public static PracticeVideoViewsResponse from(PracticeVideoViewsResult result) {
		return new PracticeVideoViewsResponse(result.targetCount(), result.viewedCount(),
				result.viewed()
					.stream()
					.map(view -> new Viewed(view.accountId(), view.name(), view.generation(), view.viewedAt()))
					.toList(),
				result.notViewed()
					.stream()
					.map(member -> new NotViewed(member.accountId(), member.name(), member.generation()))
					.toList());
	}

}
