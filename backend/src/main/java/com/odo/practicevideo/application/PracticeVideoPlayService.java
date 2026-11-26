package com.odo.practicevideo.application;

import com.odo.practicevideo.domain.exception.PracticeVideoNotFoundException;
import com.odo.practicevideo.domain.repository.PracticeVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재생 수. 08_PracticeVideo_spec.md의 "재생 수", API 명세의 "재생 수".
 * 영상 행의 숫자를 바로 1 더한다. "한 화면에서는 한 번"은 화면이 맡고, 서버는 받은 요청마다 1을 센다.
 */
@Service
@RequiredArgsConstructor
public class PracticeVideoPlayService {

	private final PracticeVideoAccess practiceVideoAccess;

	private final PracticeVideoRepository practiceVideoRepository;

	/** 볼 수 없음(그사이 삭제 포함) PracticeVideoNotFoundException, 내가 올리는 중 PracticeVideoNotReadyException. */
	@Transactional
	public void play(long accountId, long videoId) {
		practiceVideoAccess.requireReadyVideo(videoId, accountId);
		// READY는 DELETING으로만 바뀐다. 확인한 뒤 0행이면 그사이 삭제한 것이다.
		if (practiceVideoRepository.incrementPlayCount(videoId) == 0) {
			throw new PracticeVideoNotFoundException();
		}
	}

}
