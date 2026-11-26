package com.odo.practicevideo.application.dto;

import java.time.Instant;

import com.odo.common.storage.PresignedDownload;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.entity.PracticeVideoStatus;

/**
 * 영상 하나. 목록의 항목과 상세가 같이 쓴다. 08_PracticeVideo_spec.md의 "영상 목록", "영상 상세".
 *
 * @param viewedByMe 내가 대상자일 때 봤는지. 대상자가 아니면(내가 올린 영상) null
 * @param playback 상세에만 있다. 목록과 업로드 중인 영상은 null
 */
public record PracticeVideoResult(
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
		PresignedDownload playback) {

	/** viewed는 내가 이 영상의 시청 기록을 남겼는지다. 내가 올린 영상이면 대상자가 아니므로 viewedByMe가 null이다. */
	public static PracticeVideoResult of(PracticeVideo video, String songName, String uploaderName, long accountId,
			boolean viewed, PresignedDownload playback) {
		boolean mine = video.isUploadedBy(accountId);
		return new PracticeVideoResult(video.getId(), video.getPerformanceId(), video.getSongId(), songName,
				video.getTitle(), video.getDescription(), video.getUploaderAccountId(), uploaderName,
				video.getStatus(), video.getCreatedAt(), video.getPlayCount(), mine, mine ? null : viewed, playback);
	}

}
