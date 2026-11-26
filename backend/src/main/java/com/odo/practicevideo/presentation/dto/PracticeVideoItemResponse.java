package com.odo.practicevideo.presentation.dto;

import java.time.Instant;

import com.odo.practicevideo.application.dto.PracticeVideoResult;
import com.odo.practicevideo.domain.entity.PracticeVideoStatus;

/** 목록의 항목. 업로드 시작의 video도 같은 형식이다. 08_PracticeVideo_spec.md의 "영상 목록". */
public record PracticeVideoItemResponse(
		long id,
		long songId,
		String songName,
		String title,
		long uploaderAccountId,
		String uploaderName,
		PracticeVideoStatus status,
		Instant uploadedAt,
		long playCount,
		boolean mine,
		Boolean viewedByMe) {

	public static PracticeVideoItemResponse from(PracticeVideoResult result) {
		return new PracticeVideoItemResponse(result.id(), result.songId(), result.songName(), result.title(),
				result.uploaderAccountId(), result.uploaderName(), result.status(), result.uploadedAt(),
				result.playCount(), result.mine(), result.viewedByMe());
	}

}
