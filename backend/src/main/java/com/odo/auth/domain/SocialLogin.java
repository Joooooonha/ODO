package com.odo.auth.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 계정의 로그인 수단. (provider, provider_user_id)가 유일하다.
 */
@Entity
@Table(name = "social_login")
public class SocialLogin {

	public static final String KAKAO = "KAKAO";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(nullable = false, length = 20)
	private String provider;

	@Column(name = "provider_user_id", nullable = false, length = 64)
	private String providerUserId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SocialLogin() {
	}

	public SocialLogin(Long accountId, String provider, String providerUserId, Instant now) {
		this.accountId = accountId;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.createdAt = now;
	}

	public Long getAccountId() {
		return accountId;
	}

}
