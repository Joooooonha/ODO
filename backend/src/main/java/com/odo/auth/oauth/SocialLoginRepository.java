package com.odo.auth.oauth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialLoginRepository extends JpaRepository<SocialLogin, Long> {

	Optional<SocialLogin> findByProviderAndProviderUserId(String provider, String providerUserId);

}
