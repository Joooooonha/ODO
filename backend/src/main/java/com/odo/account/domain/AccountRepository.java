package com.odo.account.domain;

import java.util.Optional;

/**
 * 계정 저장소. 도메인이 필요한 동작만 정의하고, 구현은 infrastructure에 둔다.
 */
public interface AccountRepository {

	Optional<Account> findById(Long id);

	Account save(Account account);

}
