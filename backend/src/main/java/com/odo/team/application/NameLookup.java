package com.odo.team.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import com.odo.team.domain.entity.TeamRole;
import com.odo.team.domain.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 팀 응답에 넣을 계정 이름을 읽는다. 팀원과 요청은 계정을 ID로만 참조하므로 이름은 따로 읽는다.
 */
@Component
@RequiredArgsConstructor
class NameLookup {

	private final AccountRepository accountRepository;

	private final TeamMemberRepository teamMemberRepository;

	String nameOf(long accountId) {
		// 탈퇴가 없으므로 팀원과 요청의 계정은 항상 있다
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

	String leaderNameOf(long teamId) {
		long leaderId = teamMemberRepository.findByTeamIdAndRole(teamId, TeamRole.LEADER)
			.orElseThrow(() -> new IllegalStateException("팀장이 없다: " + teamId))
			.getAccountId();
		return nameOf(leaderId);
	}

}
