package com.odo.auth.domain;

import java.util.Optional;

/**
 * 로그인 수단 저장소. 구현은 infrastructure에 둔다.
 */
public interface SocialLoginRepository {

	Optional<SocialLogin> findByProviderAndProviderUserId(String provider, String providerUserId);

	/** (provider, providerUserId)가 이미 있으면 DataIntegrityViolationException. */
	SocialLogin save(SocialLogin socialLogin);

}
