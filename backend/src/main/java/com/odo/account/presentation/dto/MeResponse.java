package com.odo.account.presentation.dto;

import com.odo.account.application.AccountProfile;

public record MeResponse(long id, String name) {

	public static MeResponse from(AccountProfile profile) {
		return new MeResponse(profile.id(), profile.name());
	}

}
