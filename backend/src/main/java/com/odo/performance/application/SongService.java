package com.odo.performance.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.odo.account.application.AccountReader;
import com.odo.performance.application.dto.SongResult;
import com.odo.performance.domain.entity.Song;
import com.odo.performance.domain.exception.SongNotFoundException;
import com.odo.performance.domain.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 곡 목록, 곡 만들기, 곡 조회, 이름 수정, 삭제. 06_Performance_spec.md의 "API 명세"와 "곡 삭제".
 * 곡 URL(/api/songs/{songId})의 확인은 SongAccess가 한다.
 */
@Service
@RequiredArgsConstructor
public class SongService {

	private final SongRepository songRepository;

	private final PerformanceAccess performanceAccess;

	private final SongAccess songAccess;

	private final AccountReader accountReader;

	private final Clock clock;

	/** 삭제하지 않은 곡만, 만든 순서. 명단에 없음 PerformanceNotFoundException. */
	@Transactional(readOnly = true)
	public List<SongResult> findSongs(long accountId, long performanceId) {
		performanceAccess.requireParticipant(performanceId, accountId);
		List<Song> songs = songRepository.findAllByPerformanceIdAndDeletedAtIsNullOrderByIdAsc(performanceId);
		Map<Long, String> names = accountReader.namesOf(songs.stream().map(Song::getDirectorAccountId).toList());
		return songs.stream().map(song -> SongResult.of(song, names.get(song.getDirectorAccountId()), accountId)).toList();
	}

	/**
	 * 만든 사람이 담당 디렉터가 된다.
	 * 명단에 없음 PerformanceNotFoundException, 디렉터 아님 NotDirectorException, 이름 규칙 위반 InvalidSongNameException.
	 */
	@Transactional
	public SongResult createSong(long accountId, long performanceId, String name) {
		performanceAccess.requireDirector(performanceId, accountId);
		Song song = songRepository.save(Song.createSong(performanceId, accountId, name, clock.instant()));
		return SongResult.of(song, accountReader.nameOf(accountId), accountId);
	}

	/** 곡이 없음, 삭제됨, 명단에 없음은 SongNotFoundException. */
	@Transactional(readOnly = true)
	public SongResult findSong(long accountId, long songId) {
		Song song = songAccess.requireVisibleSong(songId, accountId);
		return SongResult.of(song, accountReader.nameOf(song.getDirectorAccountId()), accountId);
	}

	/**
	 * 담당 디렉터만 이름을 바꾼다. 지금 역할이 디렉터인지는 보지 않는다.
	 * 곡이 없음, 삭제됨, 명단에 없음 SongNotFoundException, 담당 아님 NotSongDirectorException, 이름 규칙 위반 InvalidSongNameException.
	 */
	@Transactional
	public SongResult renameSong(long accountId, long songId, String name) {
		Song song = songAccess.requireOwnSong(songId, accountId);
		String newName = Song.requireValidName(name);
		// 삭제와 동시에 와도 삭제된 곡의 이름이 바뀌지 않도록 삭제하지 않았을 때만 바꾼다
		if (songRepository.renameIfNotDeleted(songId, newName, clock.instant()) == 0) {
			throw new SongNotFoundException();
		}
		return SongResult.of(song, newName, accountReader.nameOf(accountId), accountId);
	}

	/** 삭제 표시를 한다. 동시에 두 번 오면 하나만 성공한다. 예외는 renameSong과 같다(이름 규칙 제외). */
	@Transactional
	public void deleteSong(long accountId, long songId) {
		songAccess.requireOwnSong(songId, accountId);
		if (songRepository.deleteIfNotDeleted(songId, clock.instant()) == 0) {
			throw new SongNotFoundException();
		}
	}

}
