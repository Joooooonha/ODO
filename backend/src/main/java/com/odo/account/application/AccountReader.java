package com.odo.account.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 기능이 응답에 넣을 계정 값을 읽는다. 12_Account_spec.md의 "패키지".
 * 팀원, 입장 요청, 공연 명단, 곡은 계정을 ID로만 참조하므로 이름은 여기서 따로 읽는다.
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class AccountReader {

	private final AccountRepository accountRepository;

	public String nameOf(long accountId) {
		// 탈퇴가 없으므로 다른 기능이 참조하는 계정은 항상 있다
		return accountRepository.findById(accountId)
			.map(Account::getName)
			.orElseThrow(() -> new IllegalStateException("계정이 없다: " + accountId));
	}

	/** 여러 계정의 이름을 한 번의 쿼리(IN)로 읽는다. */
	public Map<Long, String> namesOf(Collection<Long> accountIds) {
		return accountRepository.findAllById(accountIds)
			.stream()
			.collect(Collectors.toMap(Account::getId, Account::getName));
	}

}
