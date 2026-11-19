package com.odo.account.domain.repository;

import java.util.List;
import java.util.Optional;

import com.odo.account.domain.entity.Account;

/**
 * 계정 저장소. 도메인이 필요한 동작만 정의하고, 구현은 infrastructure에 둔다.
 */
public interface AccountRepository {

	Optional<Account> findById(Long id);

	/** 팀원 목록처럼 여러 계정의 이름이 필요할 때 한 번에 읽는다. 순서는 보장하지 않는다. */
	List<Account> findAllById(Iterable<Long> ids);

	Account save(Account account);

}
