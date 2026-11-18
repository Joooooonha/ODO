package com.odo.account.presentation.dto;

import com.odo.account.application.dto.AccountProfileResult;

public record MeResponse(long id, String name) {

	public static MeResponse from(AccountProfileResult profile) {
		return new MeResponse(profile.id(), profile.name());
	}

}
