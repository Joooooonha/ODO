package com.odo.practicevideo.infrastructure;

import java.time.Instant;

import com.odo.practicevideo.domain.entity.PracticeVideoView;
import com.odo.practicevideo.domain.repository.PracticeVideoViewRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** PracticeVideoViewRepository의 JPA 구현. 기록은 ON CONFLICT를 쓰므로 네이티브 쿼리다. */
interface PracticeVideoViewJpaRepository
		extends JpaRepository<PracticeVideoView, PracticeVideoView.Key>, PracticeVideoViewRepository {

	@Override
	@Modifying
	@Query(value = """
			INSERT INTO practice_video_view (video_id, account_id, viewed_at) VALUES (:videoId, :accountId, :now)
			ON CONFLICT (video_id, account_id) DO NOTHING
			""", nativeQuery = true)
	void record(@Param("videoId") long videoId, @Param("accountId") long accountId, @Param("now") Instant now);

}
