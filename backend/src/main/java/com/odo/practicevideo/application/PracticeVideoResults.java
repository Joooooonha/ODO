package com.odo.practicevideo.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.common.storage.ObjectStorage;
import com.odo.common.storage.PresignedDownload;
import com.odo.performance.domain.entity.Song;
import com.odo.performance.domain.repository.SongRepository;
import com.odo.practicevideo.application.dto.PracticeVideoResult;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.entity.PracticeVideoView;
import com.odo.practicevideo.domain.repository.PracticeVideoViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 영상 엔티티를 응답 값으로 바꾼다. 곡 이름(삭제한 곡 포함), 올린 사람 이름, 내가 봤는지를 읽어 붙이고, 상세에는 재생 주소를
 * 발급한다.
 */
@Component
@RequiredArgsConstructor
class PracticeVideoResults {

	private final SongRepository songRepository;

	private final AccountReader accountReader;

	private final PracticeVideoViewRepository viewRepository;

	private final ObjectStorage objectStorage;

	private final PracticeVideoProperties properties;

	/** 목록의 항목들. 곡 이름, 올린 사람 이름, 내가 본 영상을 한 번씩(IN) 읽는다. 재생 주소는 없다. */
	List<PracticeVideoResult> items(List<PracticeVideo> videos, long accountId) {
		Set<Long> songIds = new HashSet<>();
		Set<Long> uploaderIds = new HashSet<>();
		videos.forEach(video -> {
			songIds.add(video.getSongId());
			uploaderIds.add(video.getUploaderAccountId());
		});
		Map<Long, String> songNames = songRepository.findAllById(songIds)
			.stream()
			.collect(Collectors.toMap(Song::getId, Song::getName));
		Map<Long, String> uploaderNames = accountReader.namesOf(uploaderIds);
		Set<Long> viewed = viewedAmong(videos, accountId);
		return videos.stream()
			.map(video -> PracticeVideoResult.of(video, songNames.get(video.getSongId()),
					uploaderNames.get(video.getUploaderAccountId()), accountId, viewed.contains(video.getId()), null))
			.toList();
	}

	/** 목록의 항목 하나와 같은 형식(재생 주소 없음). 업로드 시작의 응답에 쓴다. */
	PracticeVideoResult item(PracticeVideo video, long accountId) {
		return PracticeVideoResult.of(video, songNameOf(video), accountReader.nameOf(video.getUploaderAccountId()),
				accountId, false, null);
	}

	/** 상세. READY이면 재생 주소를 발급한다. */
	PracticeVideoResult detail(PracticeVideo video, long accountId) {
		PresignedDownload playback = video.isReady()
				? objectStorage.presignDownload(video.getObjectKey(), properties.playbackUrlTtl()) : null;
		boolean viewed = !video.isUploadedBy(accountId)
				&& viewRepository.existsByVideoIdAndAccountId(video.getId(), accountId);
		return PracticeVideoResult.of(video, songNameOf(video), accountReader.nameOf(video.getUploaderAccountId()),
				accountId, viewed, playback);
	}

	/** 남이 올린 영상 중 내가 시청 기록을 남긴 영상의 ID. 내가 올린 영상은 대상자가 아니라 묻지 않는다. */
	private Set<Long> viewedAmong(List<PracticeVideo> videos, long accountId) {
		List<Long> others = videos.stream()
			.filter(video -> !video.isUploadedBy(accountId))
			.map(PracticeVideo::getId)
			.toList();
		if (others.isEmpty()) {
			return Set.of();
		}
		return viewRepository.findAllByAccountIdAndVideoIdIn(accountId, others)
			.stream()
			.map(PracticeVideoView::getVideoId)
			.collect(Collectors.toSet());
	}

	private String songNameOf(PracticeVideo video) {
		// 곡은 삭제 표시만 하고 행을 지우지 않으므로 항상 있다
		return songRepository.findById(video.getSongId())
			.map(Song::getName)
			.orElseThrow(() -> new IllegalStateException("곡이 없다: " + video.getSongId()));
	}

}
