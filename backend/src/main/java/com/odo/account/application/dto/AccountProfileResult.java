package com.odo.account.application.dto;

import com.odo.account.domain.entity.Account;

/**
 * 응용 계층이 돌려주는 계정 정보. 엔티티를 계층 밖으로 내보내지 않기 위해 쓴다.
 */
public record AccountProfileResult(
		long id,
		String name) {

	public static AccountProfileResult from(Account account) {
		return new AccountProfileResult(account.getId(), account.getName());
	}

}
