package com.odo.performance.application;

import com.odo.performance.domain.entity.Song;
import com.odo.performance.domain.exception.SongNotFoundException;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.performance.domain.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 곡 URL(/api/songs/{songId})에서 곡을 볼 수 있는지, 자기 곡인지 확인한다. 06_Performance_spec.md의 "핵심 메서드 시그니처".
 * 확인 순서: 곡이 있는지 → 그 공연의 명단에 있는지 → 담당 디렉터인지.
 * 앞의 둘은 모두 SongNotFoundException(404)이다. URL이 가리키는 자원이 곡이기 때문이다.
 * 곡 서비스와 1차 이후의 대형과 음원, 숙제, 연습 영상 서비스가 같이 쓴다. 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class SongAccess {

	private final SongRepository songRepository;

	private final PerformanceMemberRepository performanceMemberRepository;

	/** 삭제하지 않은 곡이고 그 공연의 명단에 있으면 곡을 돌려준다. 아니면 SongNotFoundException. */
	public Song requireVisibleSong(long songId, long accountId) {
		Song song = songRepository.findByIdAndDeletedAtIsNull(songId).orElseThrow(SongNotFoundException::new);
		if (performanceMemberRepository.findByPerformanceIdAndAccountId(song.getPerformanceId(), accountId).isEmpty()) {
			throw new SongNotFoundException();
		}
		return song;
	}

	/** requireVisibleSong에 더해 담당 디렉터인지 본다. 아니면 NotSongDirectorException. */
	public Song requireOwnSong(long songId, long accountId) {
		Song song = requireVisibleSong(songId, accountId);
		song.requireDirectedBy(accountId);
		return song;
	}

}
