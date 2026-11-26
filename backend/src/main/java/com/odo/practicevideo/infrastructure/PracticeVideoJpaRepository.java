package com.odo.practicevideo.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PracticeVideoRepository의 JPA 구현. 잠금과 시퀀스는 어떤 SQL이 나가는지 보이도록 네이티브 쿼리로 쓴다.
 * 조건부 UPDATE는 영속성 컨텍스트를 거치지 않고 DB에 바로 반영한다. 동시에 두 트랜잭션이 같은 행을 바꾸면 뒤의 것은 앞의 것이
 * 끝날 때까지 기다린 뒤 조건을 다시 보고 0행이 된다.
 */
interface PracticeVideoJpaRepository extends JpaRepository<PracticeVideo, Long>, PracticeVideoRepository {

	@Override
	@Query(value = "SELECT nextval(pg_get_serial_sequence('practice_video', 'id'))", nativeQuery = true)
	long nextId();

	@Override
	@Query(value = "SELECT * FROM practice_video WHERE id = :id FOR UPDATE", nativeQuery = true)
	Optional<PracticeVideo> lockById(@Param("id") long id);

	@Override
	@Query("""
			select v from PracticeVideo v
			where v.performanceId = :performanceId
			  and (v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.READY
			       or (v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.UPLOADING
			           and v.uploaderAccountId = :accountId and v.uploadDeadline > :now))
			  and v.createdAt >= :from and v.createdAt < :to
			  and (v.createdAt < :cursorUploadedAt or (v.createdAt = :cursorUploadedAt and v.id < :cursorId))
			order by v.createdAt desc, v.id desc
			""")
	List<PracticeVideo> findPage(@Param("performanceId") long performanceId, @Param("accountId") long accountId,
			@Param("now") Instant now, @Param("from") Instant from, @Param("to") Instant to,
			@Param("cursorUploadedAt") Instant cursorUploadedAt, @Param("cursorId") long cursorId, Limit limit);

	@Override
	@Modifying
	@Query("""
			update PracticeVideo v
			set v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.READY, v.readyAt = :now,
			    v.updatedAt = :now
			where v.id = :id and v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.UPLOADING
			  and v.uploadDeadline > :now
			""")
	int markReady(@Param("id") long id, @Param("now") Instant now);

	@Override
	@Modifying
	@Query("""
			update PracticeVideo v
			set v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.DELETING, v.updatedAt = :now
			where v.id = :id and v.status in (com.odo.practicevideo.domain.entity.PracticeVideoStatus.UPLOADING,
			                                 com.odo.practicevideo.domain.entity.PracticeVideoStatus.READY)
			""")
	int markDeleting(@Param("id") long id, @Param("now") Instant now);

	@Override
	@Modifying
	@Query("delete from PracticeVideo v where v.id = :id and v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.UPLOADING")
	int deleteUploading(@Param("id") long id);

	@Override
	@Modifying
	@Query("""
			delete from PracticeVideo v
			where v.id = :id and v.status = com.odo.practicevideo.domain.entity.PracticeVideoStatus.DELETING
			  and v.readyAt is not null
			""")
	int deleteDeleting(@Param("id") long id);

	@Override
	@Query(value = """
			SELECT id FROM practice_video
			WHERE (status = 'DELETING' AND (ready_at IS NOT NULL OR upload_deadline <= :now))
			   OR (status = 'UPLOADING' AND upload_deadline <= :now)
			ORDER BY id LIMIT :limit
			""", nativeQuery = true)
	List<Long> findCleanupTargetIds(@Param("now") Instant now, @Param("limit") int limit);

	@Override
	@Query(value = """
			SELECT * FROM practice_video
			WHERE id = :id
			  AND ((status = 'DELETING' AND (ready_at IS NOT NULL OR upload_deadline <= :now))
			       OR (status = 'UPLOADING' AND upload_deadline <= :now))
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<PracticeVideo> lockCleanupTargetSkipLocked(@Param("id") long id, @Param("now") Instant now);

	@Override
	@Modifying
	@Query(value = "DELETE FROM practice_video WHERE id = :id", nativeQuery = true)
	void deleteRow(@Param("id") long id);

}
