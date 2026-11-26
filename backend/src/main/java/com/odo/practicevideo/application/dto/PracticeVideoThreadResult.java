package com.odo.practicevideo.application.dto;

import java.time.Instant;
import java.util.List;

import com.odo.practicevideo.domain.entity.PracticeVideoThread;

/** 시점 스레드와 그 코멘트(단 순서). 08_PracticeVideo_spec.md의 "코멘트 목록". */
public record PracticeVideoThreadResult(long id, int positionSeconds, Instant createdAt,
		List<PracticeVideoCommentResult> comments) {

	public static PracticeVideoThreadResult of(PracticeVideoThread thread, List<PracticeVideoCommentResult> comments) {
		return new PracticeVideoThreadResult(thread.getId(), thread.getPositionSeconds(), thread.getCreatedAt(),
				comments);
	}

}
