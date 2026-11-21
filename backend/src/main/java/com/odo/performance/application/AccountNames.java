package com.odo.performance.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공연 응답에 넣을 계정 이름을 읽는다. 명단과 곡은 계정을 ID로만 참조하므로 이름은 따로 읽는다.
 * 팀의 NameLookup은 팀 패키지 안에서만 쓰는 클래스라 가져다 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
class AccountNames {

	private final AccountRepository accountRepository;

	String nameOf(long accountId) {
		// 탈퇴가 없으므로 명단과 곡의 계정은 항상 있다
		return accountRepository.findById(accountId)
			.map(Account::getName)
			.orElseThrow(() -> new IllegalStateException("계정이 없다: " + accountId));
	}

	/** 여러 계정의 이름을 한 번의 쿼리로 읽는다. */
	Map<Long, String> namesOf(Collection<Long> accountIds) {
		return accountRepository.findAllById(accountIds)
			.stream()
			.collect(Collectors.toMap(Account::getId, Account::getName));
	}

}
