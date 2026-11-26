package com.odo.practicevideo.application.dto;

import java.time.Instant;

import com.odo.common.storage.PresignedDownload;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.entity.PracticeVideoStatus;

/**
 * 영상 하나. 목록의 항목과 상세가 같이 쓴다. 08_PracticeVideo_spec.md의 "영상 목록", "영상 상세".
 *
 * @param viewedByMe 내가 대상자일 때 봤는지. 대상자가 아니거나 아직 시청 기록이 없으면(PR A) null
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

	public static PracticeVideoResult of(PracticeVideo video, String songName, String uploaderName, long accountId,
			PresignedDownload playback) {
		return new PracticeVideoResult(video.getId(), video.getPerformanceId(), video.getSongId(), songName,
				video.getTitle(), video.getDescription(), video.getUploaderAccountId(), uploaderName,
				video.getStatus(), video.getCreatedAt(), video.getPlayCount(), video.isUploadedBy(accountId), null,
				playback);
	}

}
