package com.odo.account.application;

import java.time.Clock;

import com.odo.account.application.dto.AccountProfileResult;
import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 조회와 이름 변경. 이름 규칙은 Account가 지킨다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

	private final AccountRepository accountRepository;

	private final Clock clock;

	@Transactional(readOnly = true)
	public AccountProfileResult getProfile(long accountId) {
		return AccountProfileResult.from(find(accountId));
	}

	/** 이름 규칙에 맞지 않으면 InvalidNameException. */
	@Transactional
	public AccountProfileResult changeName(long accountId, String newName) {
		Account account = find(accountId);
		account.changeName(newName, clock.instant());
		return AccountProfileResult.from(account);
	}

	private Account find(long accountId) {
		// 탈퇴가 없으므로 서명이 맞은 토큰의 계정은 항상 있다
		return accountRepository.findById(accountId)
			.orElseThrow(() -> new IllegalStateException("토큰의 계정이 없다: " + accountId));
	}

}
