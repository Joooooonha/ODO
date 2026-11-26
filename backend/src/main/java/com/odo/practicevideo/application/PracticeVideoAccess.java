package com.odo.practicevideo.application;

import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.exception.NotPracticeVideoUploaderException;
import com.odo.practicevideo.domain.exception.PracticeVideoNotFoundException;
import com.odo.practicevideo.domain.exception.PracticeVideoNotReadyException;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 영상 URL(/api/practice-videos/{id})에서 영상을 볼 수 있는지, 다룰 수 있는지 확인한다. 08_PracticeVideo_spec.md의 "권한 확인".
 * 없는 것으로 보는 경우(404)는 모두 PracticeVideoNotFoundException이다. URL이 가리키는 자원이 영상이기 때문이다.
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class PracticeVideoAccess {

	private final PracticeVideoRepository practiceVideoRepository;

	private final PerformanceMemberRepository performanceMemberRepository;

	/**
	 * 영상이 있고, 삭제 중이 아니고, 그 공연의 명단에 있고, 남이 올리는 중이 아니면 돌려준다. 아니면 PracticeVideoNotFoundException.
	 * 내가 올리는 중인 영상은 돌려준다(완료 기한이 지나도).
	 */
	public PracticeVideo requireVisibleVideo(long videoId, long accountId) {
		PracticeVideo video = practiceVideoRepository.findById(videoId)
			.filter(found -> !found.isDeleting())
			.orElseThrow(PracticeVideoNotFoundException::new);
		if (performanceMemberRepository.findByPerformanceIdAndAccountId(video.getPerformanceId(), accountId)
			.isEmpty()) {
			throw new PracticeVideoNotFoundException();
		}
		if (video.isUploading() && !video.isUploadedBy(accountId)) {
			throw new PracticeVideoNotFoundException();
		}
		return video;
	}

	/** requireVisibleVideo에 더해, 내가 올리는 중이면 PracticeVideoNotReadyException(409). */
	public PracticeVideo requireReadyVideo(long videoId, long accountId) {
		PracticeVideo video = requireVisibleVideo(videoId, accountId);
		requireReady(video);
		return video;
	}

	/**
	 * 영상 행을 FOR KEY SHARE로 잠근 뒤 requireReadyVideo와 같이 확인한다. 영상에 딸린 행을 넣기 전에 쓴다.
	 * 확인과 넣기 사이에 삭제가 행을 지우면 넣기가 FK 위반(500)이 되므로, 행 삭제가 이 트랜잭션의 커밋을 기다리게 한다.
	 * 잠금 쿼리가 영속성 컨텍스트에 처음 읽어 넣어야 확인이 잠근 뒤의 상태를 보므로, 이 영상을 먼저 읽지 않은 트랜잭션에서 부른다.
	 */
	public PracticeVideo lockReadyVideo(long videoId, long accountId) {
		practiceVideoRepository.lockByIdForKeyShare(videoId);
		return requireReadyVideo(videoId, accountId);
	}

	/** 올린 디렉터 본인이 아니면 NotPracticeVideoUploaderException(403). 지금 역할이 디렉터인지는 보지 않는다. */
	public void requireUploader(PracticeVideo video, long accountId) {
		if (!video.isUploadedBy(accountId)) {
			throw new NotPracticeVideoUploaderException();
		}
	}

	public void requireReady(PracticeVideo video) {
		if (!video.isReady()) {
			throw new PracticeVideoNotReadyException();
		}
	}

}
