package com.odo.practicevideo.domain.repository;

import java.util.List;

import com.odo.practicevideo.domain.entity.PracticeVideoComment;

public interface PracticeVideoCommentRepository {

	PracticeVideoComment save(PracticeVideoComment comment);

	/** 영상의 모든 코멘트. 스레드마다 단 순서(id)다. index (video_id, thread_id, id)를 쓴다. */
	List<PracticeVideoComment> findAllByVideoIdOrderByThreadIdAscIdAsc(Long videoId);

}
