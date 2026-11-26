package com.odo.practicevideo.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.odo.performance.application.PerformanceAccess;
import com.odo.practicevideo.application.dto.PracticeVideoPageResult;
import com.odo.practicevideo.application.dto.PracticeVideoResult;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.exception.InvalidDateRangeException;
import com.odo.practicevideo.domain.exception.PracticeVideoNotFoundException;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 영상 목록, 상세, 수정, 삭제. 08_PracticeVideo_spec.md의 "목록과 날짜 검색", "재생", "삭제", API 명세.
 */
@Service
@RequiredArgsConstructor
public class PracticeVideoService {

	/** 날짜 검색은 한국 시간의 업로드일 기준이다. */
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	/** 검색 조건이 없을 때의 범위와 첫 페이지의 커서. 모든 업로드 시각을 덮는다. */
	private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");

	private static final Instant LATEST = Instant.parse("9999-12-31T00:00:00Z");

	private final PracticeVideoRepository practiceVideoRepository;

	private final PerformanceAccess performanceAccess;

	private final PracticeVideoAccess practiceVideoAccess;

	private final PracticeVideoResults results;

	private final PracticeVideoObjectRemover objectRemover;

	private final PracticeVideoProperties properties;

	private final Clock clock;

	/**
	 * 최근에 올린 것부터 page-size개. 다음 페이지가 있으면 nextCursor를 준다.
	 * 명단에 없음 PerformanceNotFoundException, from이 to보다 늦음 InvalidDateRangeException, 커서를 읽지 못함 InvalidCursorException.
	 */
	@Transactional(readOnly = true)
	public PracticeVideoPageResult findVideos(long accountId, long performanceId, LocalDate from, LocalDate to,
			String cursor) {
		performanceAccess.requireParticipant(performanceId, accountId);
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidDateRangeException();
		}
		Instant start = from == null ? EARLIEST : from.atStartOfDay(KOREA).toInstant();
		Instant end = to == null ? LATEST : to.plusDays(1).atStartOfDay(KOREA).toInstant();
		PracticeVideoCursor after = cursor == null ? new PracticeVideoCursor(LATEST, Long.MAX_VALUE)
				: PracticeVideoCursor.decode(cursor);
		int pageSize = properties.pageSize();
		// 하나 더 읽어 다음 페이지가 있는지 안다
		List<PracticeVideo> found = practiceVideoRepository.findPage(performanceId, accountId, clock.instant(), start,
				end, after.uploadedAt(), after.id(), Limit.of(pageSize + 1));
		List<PracticeVideo> page = found.size() > pageSize ? found.subList(0, pageSize) : found;
		String nextCursor = null;
		if (found.size() > pageSize) {
			PracticeVideo last = page.get(page.size() - 1);
			nextCursor = PracticeVideoCursor.encode(last.getCreatedAt(), last.getId());
		}
		return new PracticeVideoPageResult(results.items(page, accountId), nextCursor);
	}

	/** 볼 수 없음 PracticeVideoNotFoundException, 내가 올리는 중 PracticeVideoNotReadyException. 재생 주소를 발급한다. */
	@Transactional(readOnly = true)
	public PracticeVideoResult findVideo(long accountId, long videoId) {
		PracticeVideo video = practiceVideoAccess.requireReadyVideo(videoId, accountId);
		return results.detail(video, accountId);
	}

	/**
	 * 올린 디렉터만 제목과 설명을 고친다. 행을 먼저 잠그고 확인해, 동시에 들어온 삭제의 상태 변경을 덮지 않는다.
	 * 볼 수 없음 PracticeVideoNotFoundException, 올린 사람 아님 NotPracticeVideoUploaderException,
	 * 업로드 중 PracticeVideoNotReadyException, 규칙 위반 각자의 예외.
	 */
	@Transactional
	public PracticeVideoResult updateVideo(long accountId, long videoId, String title, String description) {
		// 잠금 쿼리가 영속성 컨텍스트에 처음 읽어 넣어야 아래 확인이 잠근 뒤의 최신 상태를 본다.
		// 먼저 읽어 둔 엔티티가 있으면 네이티브 쿼리는 그 엔티티를 새로 읽지 않는다.
		practiceVideoRepository.lockById(videoId);
		PracticeVideo video = practiceVideoAccess.requireVisibleVideo(videoId, accountId);
		practiceVideoAccess.requireUploader(video, accountId);
		practiceVideoAccess.requireReady(video);
		video.changeInfo(title, description, clock.instant());
		return results.detail(video, accountId);
	}

	/**
	 * 올린 디렉터만 지운다. 업로드 중인 영상도 지울 수 있다. DELETING으로 바꾸고, 커밋 뒤 R2 객체와 행을 지운다.
	 * 업로드 중에 지운 영상의 행은 완료 기한까지 남는다(PracticeVideoObjectRemover.removeDeleted).
	 * 볼 수 없음(이미 삭제함 포함) PracticeVideoNotFoundException, 올린 사람 아님 NotPracticeVideoUploaderException.
	 */
	@Transactional
	public void deleteVideo(long accountId, long videoId) {
		PracticeVideo video = practiceVideoAccess.requireVisibleVideo(videoId, accountId);
		practiceVideoAccess.requireUploader(video, accountId);
		if (practiceVideoRepository.markDeleting(videoId, clock.instant()) == 0) {
			throw new PracticeVideoNotFoundException();
		}
		String objectKey = video.getObjectKey();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				objectRemover.removeDeleted(videoId, objectKey);
			}
		});
	}

}
