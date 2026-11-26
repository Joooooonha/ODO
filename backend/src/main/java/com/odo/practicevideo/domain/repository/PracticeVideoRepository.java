package com.odo.practicevideo.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.practicevideo.domain.entity.PracticeVideo;
import org.springframework.data.domain.Limit;

public interface PracticeVideoRepository {

	/** 새 영상의 ID를 시퀀스에서 받는다. 객체 키에 ID가 들어가 행을 넣기 전에 필요하다. */
	long nextId();

	PracticeVideo save(PracticeVideo video);

	Optional<PracticeVideo> findById(Long id);

	/** 수정 전에 행을 FOR UPDATE로 잠근다. 동시에 들어온 삭제의 상태 변경이 수정의 저장에 덮이지 않게 한다. */
	Optional<PracticeVideo> lockById(long id);

	/**
	 * 목록. 그 공연의 READY와, 내가 올렸고 완료 기한이 지나지 않은 UPLOADING 중 업로드 시각이 [from, to)이고
	 * (업로드 시각, ID)가 커서보다 앞(작은)인 것. (업로드 시각, ID) 내림차순.
	 */
	List<PracticeVideo> findPage(long performanceId, long accountId, Instant now, Instant from, Instant to,
			Instant cursorUploadedAt, long cursorId, Limit limit);

	/** UPLOADING이고 완료 기한 안일 때만 READY로 바꾼다(조건부 UPDATE). 바뀐 행 수(0 또는 1). */
	int markReady(long id, Instant now);

	/** UPLOADING이나 READY일 때만 DELETING으로 바꾼다(조건부 UPDATE). 동시에 두 번 와도 하나만 1을 받는다. */
	int markDeleting(long id, Instant now);

	/** READY인 영상의 재생 수를 1 더하고, 바뀐 행 수를 돌려준다. 그사이 삭제했으면 0이다. */
	int incrementPlayCount(long id);

	/** UPLOADING인 행을 지운다(올라온 파일이 달라 업로드를 버릴 때). */
	int deleteUploading(long id);

	/**
	 * DELETING이고 한 번이라도 READY였던(ready_at이 있는) 행을 지운다(R2 객체를 지운 뒤).
	 * 업로드 중에 지운 행은 지우지 않는다. 진행 중이던 PUT이 나중에 끝날 수 있어 완료 기한까지 남겨 두고 청소 작업이 지운다.
	 */
	int deleteDeleting(long id);

	/**
	 * 청소 대상의 ID. 오래된 것부터 limit개. 대상은 READY였다가 지운 DELETING, 업로드 중에 지웠고 완료 기한이 지난 DELETING,
	 * 완료 기한이 지난 UPLOADING이다.
	 */
	List<Long> findCleanupTargetIds(Instant now, int limit);

	/** 아직 청소 대상이면 FOR UPDATE SKIP LOCKED로 잠근다. 다른 서버가 잠갔거나 이미 지워졌으면 비어 있다. */
	Optional<PracticeVideo> lockCleanupTargetSkipLocked(long id, Instant now);

	/** 청소 대상 행을 지운다(잠근 뒤). */
	void deleteRow(long id);

}
