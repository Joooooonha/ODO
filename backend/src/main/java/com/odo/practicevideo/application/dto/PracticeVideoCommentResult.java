package com.odo.practicevideo.application.dto;

import java.time.Instant;

import com.odo.practicevideo.domain.entity.PracticeVideoComment;

/** 스레드의 코멘트 하나. 08_PracticeVideo_spec.md의 "코멘트 목록". mine은 내가 썼는지다. */
public record PracticeVideoCommentResult(long id, long authorAccountId, String authorName, String content,
		Instant createdAt, boolean mine) {

	public static PracticeVideoCommentResult of(PracticeVideoComment comment, String authorName, long accountId) {
		return new PracticeVideoCommentResult(comment.getId(), comment.getAuthorAccountId(), authorName,
				comment.getContent(), comment.getCreatedAt(), comment.getAuthorAccountId() == accountId);
	}

}
