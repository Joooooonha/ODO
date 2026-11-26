package com.odo.practicevideo.domain.repository;

import java.util.List;
import java.util.Optional;

import com.odo.practicevideo.domain.entity.PracticeVideoThread;

public interface PracticeVideoThreadRepository {

	PracticeVideoThread save(PracticeVideoThread thread);

	Optional<PracticeVideoThread> findById(Long id);

	/** 코멘트 목록의 스레드. 시점 순, 같으면 만든 순서(id). index (video_id, position_seconds, id)를 쓴다. */
	List<PracticeVideoThread> findAllByVideoIdOrderByPositionSecondsAscIdAsc(Long videoId);

}
