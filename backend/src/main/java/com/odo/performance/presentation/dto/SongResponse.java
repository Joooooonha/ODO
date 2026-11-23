package com.odo.performance.presentation.dto;

import com.odo.performance.application.dto.SongResult;

public record SongResponse(
		long id,
		long performanceId,
		String name,
		long directorAccountId,
		String directorName,
		boolean mine) {

	public static SongResponse from(SongResult result) {
		return new SongResponse(result.id(), result.performanceId(), result.name(), result.directorAccountId(),
				result.directorName(), result.mine());
	}

}
