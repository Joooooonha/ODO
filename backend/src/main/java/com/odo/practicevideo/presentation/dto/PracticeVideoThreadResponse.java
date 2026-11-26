package com.odo.practicevideo.presentation.dto;

import java.time.Instant;
import java.util.List;

import com.odo.practicevideo.application.dto.PracticeVideoThreadResult;

/** 시점 스레드. 스레드 만들기의 응답도 같은 형식(코멘트 하나)이다. 08_PracticeVideo_spec.md의 "코멘트 목록". */
public record PracticeVideoThreadResponse(long id, int positionSeconds, Instant createdAt,
		List<PracticeVideoCommentResponse> comments) {

	public static PracticeVideoThreadResponse from(PracticeVideoThreadResult result) {
		return new PracticeVideoThreadResponse(result.id(), result.positionSeconds(), result.createdAt(),
				result.comments().stream().map(PracticeVideoCommentResponse::from).toList());
	}

}
