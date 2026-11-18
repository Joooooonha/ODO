package com.odo.auth.infrastructure;

import com.odo.auth.domain.SocialLogin;
import com.odo.auth.domain.SocialLoginRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SocialLoginRepository의 JPA 구현. Spring Data가 구현체를 만든다.
 */
interface SocialLoginJpaRepository extends JpaRepository<SocialLogin, Long>, SocialLoginRepository {

}
