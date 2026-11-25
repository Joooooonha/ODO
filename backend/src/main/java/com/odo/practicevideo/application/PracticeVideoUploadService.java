package com.odo.practicevideo.application;

import java.time.Clock;
import java.time.Instant;

import com.odo.common.error.InvalidRequestException;
import com.odo.common.storage.ObjectStorage;
import com.odo.common.storage.PresignedUpload;
import com.odo.common.storage.StorageUnavailableException;
import com.odo.common.storage.StoredObject;
import com.odo.performance.application.PerformanceAccess;
import com.odo.performance.domain.entity.Song;
import com.odo.performance.domain.repository.SongRepository;
import com.odo.practicevideo.application.dto.PracticeVideoResult;
import com.odo.practicevideo.application.dto.StartUploadCommand;
import com.odo.practicevideo.application.dto.UploadStartResult;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.exception.InvalidSongException;
import com.odo.practicevideo.domain.exception.PracticeVideoNotFoundException;
import com.odo.practicevideo.domain.exception.UploadDeadlinePassedException;
import com.odo.practicevideo.domain.exception.UploadedFileMismatchException;
import com.odo.practicevideo.domain.exception.UploadedFileNotFoundException;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업로드 시작과 완료. 08_PracticeVideo_spec.md의 "업로드", "업로드 시작과 완료", API 명세의 "업로드 시작", "업로드 완료".
 * 완료는 R2를 부르는 동안 DB 연결과 행 잠금을 잡지 않으려고 트랜잭션을 나눈다. 상태는 조건부 UPDATE로 바꿔, 동시에 두 번
 * 부르거나 삭제, 청소와 겹쳐도 하나로 정해진다.
 */
@Service
public class PracticeVideoUploadService {

	private static final Logger log = LoggerFactory.getLogger(PracticeVideoUploadService.class);

	private final PracticeVideoRepository practiceVideoRepository;

	private final SongRepository songRepository;

	private final PerformanceAccess performanceAccess;

	private final PracticeVideoAccess practiceVideoAccess;

	private final PracticeVideoResults results;

	private final ObjectStorage objectStorage;

	private final PracticeVideoProperties properties;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	public PracticeVideoUploadService(PracticeVideoRepository practiceVideoRepository, SongRepository songRepository,
			PerformanceAccess performanceAccess, PracticeVideoAccess practiceVideoAccess, PracticeVideoResults results,
			ObjectStorage objectStorage, PracticeVideoProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.practiceVideoRepository = practiceVideoRepository;
		this.songRepository = songRepository;
		this.performanceAccess = performanceAccess;
		this.practiceVideoAccess = practiceVideoAccess;
		this.results = results;
		this.objectStorage = objectStorage;
		this.properties = properties;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 확인 순서는 API 명세의 오류 표 순서다. 권한(공연, 디렉터, 곡)을 입력보다 먼저 본다.
	 * 명단에 없음 PerformanceNotFoundException, 디렉터 아님 NotDirectorException, 곡이 틀림 InvalidSongException,
	 * 곡 담당 아님 NotSongDirectorException, 그다음 제목, 설명, 크기, 형식의 예외.
	 */
	public UploadStartResult start(long accountId, long performanceId, StartUploadCommand command) {
		return transactionTemplate.execute(status -> {
			performanceAccess.requireDirector(performanceId, accountId);
			if (command.songId() == null) {
				throw new InvalidRequestException();
			}
			Song song = songRepository.findByIdAndDeletedAtIsNull(command.songId())
				.filter(found -> found.getPerformanceId() == performanceId)
				.orElseThrow(InvalidSongException::new);
			song.requireDirectedBy(accountId);
			if (command.fileSize() == null || command.contentType() == null) {
				throw new InvalidRequestException();
			}
			PracticeVideo video = PracticeVideo.startUpload(practiceVideoRepository.nextId(), performanceId,
					song.getId(), accountId, command.title(), command.description(), command.fileSize(),
					command.contentType(), clock.instant(), properties.uploadDeadline());
			practiceVideoRepository.save(video);
			PresignedUpload upload = objectStorage.presignUpload(video.getObjectKey(), video.getContentType(),
					video.getFileSize(), properties.uploadUrlTtl());
			return new UploadStartResult(results.item(video, accountId), upload);
		});
	}

	/**
	 * 08_PracticeVideo_spec.md의 "업로드 시작과 완료"의 순서. 이미 READY이면 아무것도 바꾸지 않고 상세를 돌려준다.
	 * 볼 수 없음 PracticeVideoNotFoundException, 디렉터 지정 해제 NotDirectorException, 기한 지남 UploadDeadlinePassedException,
	 * 파일 없음 UploadedFileNotFoundException, 크기나 형식이 다름 UploadedFileMismatchException,
	 * 저장소에 닿지 못함 StorageUnavailableException.
	 */
	public PracticeVideoResult complete(long accountId, long videoId) {
		PracticeVideo video = transactionTemplate.execute(status -> {
			PracticeVideo found = practiceVideoAccess.requireVisibleVideo(videoId, accountId);
			if (!found.isUploadedBy(accountId)) {
				throw new PracticeVideoNotFoundException();
			}
			if (found.isUploading()) {
				performanceAccess.requireDirector(found.getPerformanceId(), accountId);
				if (found.isUploadExpired(clock.instant())) {
					throw new UploadDeadlinePassedException();
				}
			}
			return found;
		});
		if (video.isReady()) {
			return detail(video, accountId);
		}
		StoredObject uploaded = objectStorage.find(video.getObjectKey()).orElseThrow(UploadedFileNotFoundException::new);
		if (!video.matchesUploadedFile(uploaded.size(), uploaded.contentType())) {
			discard(video);
			throw new UploadedFileMismatchException();
		}
		Instant now = clock.instant();
		Integer changed = transactionTemplate.execute(status -> practiceVideoRepository.markReady(videoId, now));
		if (changed == null || changed == 0) {
			// 그사이 다른 요청이 완료했거나, 삭제했거나, 기한이 지났다
			PracticeVideo current = practiceVideoRepository.findById(videoId)
				.filter(found -> !found.isDeleting())
				.orElseThrow(PracticeVideoNotFoundException::new);
			if (!current.isReady()) {
				throw new UploadDeadlinePassedException();
			}
		}
		return transactionTemplate
			.execute(status -> results.detail(practiceVideoRepository.findById(videoId).orElseThrow(), accountId));
	}

	private PracticeVideoResult detail(PracticeVideo video, long accountId) {
		return transactionTemplate.execute(status -> results.detail(video, accountId));
	}

	/**
	 * 올라온 파일이 달라 업로드를 버린다. 객체를 지운 뒤 행을 지운다. 객체를 지우지 못하면 행을 남겨 두고,
	 * 완료 기한 뒤 청소 작업이 객체와 행을 함께 지운다. 행만 먼저 지우면 객체가 어디 있는지 기록이 사라진다.
	 */
	private void discard(PracticeVideo video) {
		try {
			objectStorage.delete(video.getObjectKey());
		}
		catch (StorageUnavailableException ex) {
			log.warn("크기나 형식이 다른 업로드 파일을 지우지 못했다. 완료 기한 뒤 청소 작업이 지운다: videoId={}", video.getId(), ex);
			return;
		}
		transactionTemplate.executeWithoutResult(status -> practiceVideoRepository.deleteUploading(video.getId()));
	}

}
