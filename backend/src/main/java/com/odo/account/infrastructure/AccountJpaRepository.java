package com.odo.account.infrastructure;

import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AccountRepository의 JPA 구현. Spring Data가 구현체를 만든다.
 */
interface AccountJpaRepository extends JpaRepository<Account, Long>, AccountRepository {

}
