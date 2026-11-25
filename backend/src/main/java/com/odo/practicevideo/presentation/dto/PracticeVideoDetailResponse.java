package com.odo.practicevideo.presentation.dto;

import java.time.Instant;

import com.odo.practicevideo.application.dto.PracticeVideoResult;
import com.odo.practicevideo.domain.entity.PracticeVideoStatus;

/** 영상 상세. 08_PracticeVideo_spec.md의 "영상 상세". playback은 3시간 뒤 만료되는 재생 주소다. */
public record PracticeVideoDetailResponse(
		long id,
		long performanceId,
		long songId,
		String songName,
		String title,
		String description,
		long uploaderAccountId,
		String uploaderName,
		PracticeVideoStatus status,
		Instant uploadedAt,
		long playCount,
		boolean mine,
		Boolean viewedByMe,
		Playback playback) {

	public record Playback(String url, Instant expiresAt) {

	}

	public static PracticeVideoDetailResponse from(PracticeVideoResult result) {
		Playback playback = result.playback() == null ? null
				: new Playback(result.playback().url(), result.playback().expiresAt());
		return new PracticeVideoDetailResponse(result.id(), result.performanceId(), result.songId(),
				result.songName(), result.title(), result.description(), result.uploaderAccountId(),
				result.uploaderName(), result.status(), result.uploadedAt(), result.playCount(), result.mine(),
				result.viewedByMe(), playback);
	}

}
