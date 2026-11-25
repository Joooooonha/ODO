package com.odo.schedule.presentation.dto;

import com.odo.common.error.InvalidRequestException;
import com.odo.schedule.domain.entity.Vote;

/** 투표의 본문. choice가 없거나 YES, NO가 아니면 INVALID_REQUEST다. */
public record CastVoteRequest(
		Vote choice) {

	public Vote requireChoice() {
		if (choice == null) {
			throw new InvalidRequestException();
		}
		return choice;
	}

}
