package com.odo.practicevideo.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.odo.common.storage.ObjectStorage;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R2 객체와 영상 행을 지운다. 08_PracticeVideo_spec.md의 "삭제", "청소 작업", 구현 방법의 "삭제와 청소".
 * 객체를 먼저 지우고 행을 지운다. 행이 남아 있으면 객체가 어디 있는지 알 수 있어 다시 지울 수 있다.
 * 객체 삭제는 여러 번 해도 결과가 같으므로, 같은 영상을 두 곳에서 지워도 안전하다.
 */
@Component
public class PracticeVideoObjectRemover {

	private static final Logger log = LoggerFactory.getLogger(PracticeVideoObjectRemover.class);

	/** 한 번에 고르는 청소 대상 수. 남은 대상은 다음 주기나 다른 서버가 처리한다. */
	static final int MAX_TARGETS_PER_RUN = 100;

	private final PracticeVideoRepository practiceVideoRepository;

	private final ObjectStorage objectStorage;

	private final Clock clock;

	/** 삭제의 커밋 뒤(afterCommit)에도 쓰므로 늘 새 트랜잭션을 연다. 끝난 트랜잭션에 합류하지 않게 한다. */
	private final TransactionTemplate newTransaction;

	public PracticeVideoObjectRemover(PracticeVideoRepository practiceVideoRepository, ObjectStorage objectStorage,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.practiceVideoRepository = practiceVideoRepository;
		this.objectStorage = objectStorage;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 삭제한 영상(DELETING)의 객체와 행을 지운다. 삭제 요청의 커밋 뒤에 부른다. 실패하면 로그만 남긴다.
	 * 응답은 이미 정해진 204이고, 남은 DELETING 행은 청소 작업이 다시 지운다.
	 */
	public void removeDeleted(long videoId, String objectKey) {
		try {
			objectStorage.delete(objectKey);
			newTransaction.executeWithoutResult(status -> practiceVideoRepository.deleteDeleting(videoId));
		}
		catch (RuntimeException ex) {
			log.warn("삭제한 연습 영상의 객체를 지우지 못했다. 청소 작업이 다시 지운다: videoId={}", videoId, ex);
		}
	}

	/**
	 * 청소 대상(DELETING, 완료 기한이 지난 UPLOADING)의 객체와 행을 지우고, 지운 영상 수를 돌려준다.
	 * 영상마다 트랜잭션이 따로다. 행을 SKIP LOCKED로 잠가 한 영상은 한 곳만 처리한다. 한 영상에서 오류가 나면 그 영상만
	 * 롤백하고 다음 영상으로 넘어간다. 실패한 영상은 다음 주기에 다시 처리된다.
	 */
	public int removeExpired() {
		Instant now = clock.instant();
		int removed = 0;
		List<Long> targets = practiceVideoRepository.findCleanupTargetIds(now, MAX_TARGETS_PER_RUN);
		for (Long videoId : targets) {
			try {
				Boolean done = newTransaction.execute(status -> removeIfTarget(videoId, now));
				if (Boolean.TRUE.equals(done)) {
					removed++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("연습 영상을 청소하지 못했다. 다음 주기에 다시 한다: videoId={}", videoId, ex);
			}
		}
		return removed;
	}

	private boolean removeIfTarget(long videoId, Instant now) {
		PracticeVideo video = practiceVideoRepository.lockCleanupTargetSkipLocked(videoId, now).orElse(null);
		if (video == null) {
			// 다른 곳이 처리 중이거나 이미 지웠다
			return false;
		}
		objectStorage.delete(video.getObjectKey());
		practiceVideoRepository.deleteRow(videoId);
		return true;
	}

}
