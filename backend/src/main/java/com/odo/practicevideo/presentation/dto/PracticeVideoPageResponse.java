package com.odo.practicevideo.presentation.dto;

import java.util.List;

import com.odo.practicevideo.application.dto.PracticeVideoPageResult;

/** 목록 한 페이지. nextCursor는 다음 페이지가 없으면 null이다. */
public record PracticeVideoPageResponse(List<PracticeVideoItemResponse> items, String nextCursor) {

	public static PracticeVideoPageResponse from(PracticeVideoPageResult result) {
		return new PracticeVideoPageResponse(result.items().stream().map(PracticeVideoItemResponse::from).toList(),
				result.nextCursor());
	}

}
