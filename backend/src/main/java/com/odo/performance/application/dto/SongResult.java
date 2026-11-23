package com.odo.performance.application.dto;

import com.odo.performance.domain.entity.Song;

/** 곡. mine은 보는 사람이 담당 디렉터인지다(수정과 삭제 버튼을 보일지). */
public record SongResult(
		long id,
		long performanceId,
		String name,
		long directorAccountId,
		String directorName,
		boolean mine) {

	public static SongResult of(Song song, String name, String directorName, long viewerId) {
		return new SongResult(song.getId(), song.getPerformanceId(), name, song.getDirectorAccountId(), directorName,
				song.isDirectedBy(viewerId));
	}

	public static SongResult of(Song song, String directorName, long viewerId) {
		return of(song, song.getName(), directorName, viewerId);
	}

}
