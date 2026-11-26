package com.odo.practicevideo.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.odo.practicevideo.domain.entity.PracticeVideoView;

public interface PracticeVideoViewRepository {

	/** 처음이면 기록하고, 이미 있으면 처음 시각을 그대로 둔다(INSERT ... ON CONFLICT DO NOTHING). */
	void record(long videoId, long accountId, Instant now);

	/** 그 영상의 시청 기록. 먼저 본 순서이고, 같은 시각이면 계정 ID 순서다. */
	List<PracticeVideoView> findAllByVideoIdOrderByViewedAtAscAccountIdAsc(Long videoId);

	boolean existsByVideoIdAndAccountId(Long videoId, Long accountId);

	/** 목록의 viewedByMe. 그 영상들 중 내가 본 것. 기본 키 (video_id, account_id)로 찾는다. */
	List<PracticeVideoView> findAllByAccountIdAndVideoIdIn(Long accountId, Collection<Long> videoIds);

}
