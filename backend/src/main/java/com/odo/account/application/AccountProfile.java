package com.odo.account.application;

import com.odo.account.domain.Account;

/**
 * 응용 계층이 돌려주는 계정 정보. 엔티티를 계층 밖으로 내보내지 않기 위해 쓴다.
 */
public record AccountProfile(long id, String name) {

	static AccountProfile from(Account account) {
		return new AccountProfile(account.getId(), account.getName());
	}

}
