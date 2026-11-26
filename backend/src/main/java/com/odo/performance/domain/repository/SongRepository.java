package com.odo.performance.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.performance.domain.entity.Song;

public interface SongRepository {

	Song save(Song song);

	/** 삭제한 곡도 찾는다. 삭제한 곡의 이름이 연습 영상에 남아야 한다. */
	Optional<Song> findById(Long id);

	/** 삭제한 곡도 찾는다. 여러 곡의 이름을 한 번의 쿼리(IN)로 읽는다. */
	List<Song> findAllById(Iterable<Long> ids);

	/** 삭제하지 않은 곡. */
	Optional<Song> findByIdAndDeletedAtIsNull(Long id);

	/** 곡 목록. 삭제하지 않은 곡만, 만든 순서. */
	List<Song> findAllByPerformanceIdAndDeletedAtIsNullOrderByIdAsc(Long performanceId);

	/** 삭제하지 않았을 때만 이름을 바꾼다(조건부 UPDATE). 바뀐 행 수(0 또는 1)를 돌려준다. */
	int renameIfNotDeleted(Long id, String name, Instant updatedAt);

	/** 삭제하지 않았을 때만 삭제 시각을 적는다(조건부 UPDATE). 동시에 두 번 와도 하나만 1을 받는다. */
	int deleteIfNotDeleted(Long id, Instant deletedAt);

}
