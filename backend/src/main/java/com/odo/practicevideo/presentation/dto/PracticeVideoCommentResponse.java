package com.odo.practicevideo.presentation.dto;

import java.time.Instant;

import com.odo.practicevideo.application.dto.PracticeVideoCommentResult;

/** 코멘트 하나. 스레드에 답하기의 응답도 같은 형식이다. 08_PracticeVideo_spec.md의 "코멘트 목록". */
public record PracticeVideoCommentResponse(long id, long authorAccountId, String authorName, String content,
		Instant createdAt, boolean mine) {

	public static PracticeVideoCommentResponse from(PracticeVideoCommentResult result) {
		return new PracticeVideoCommentResponse(result.id(), result.authorAccountId(), result.authorName(),
				result.content(), result.createdAt(), result.mine());
	}

}
