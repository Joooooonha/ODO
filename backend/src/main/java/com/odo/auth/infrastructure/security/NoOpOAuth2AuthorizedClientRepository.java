package com.odo.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 카카오 토큰을 저장하지 않는 OAuth2AuthorizedClientRepository.
 * <p>
 * 지정하지 않으면 Spring이 로그인마다 카카오 access token과 refresh token을 서버 메모리(InMemoryOAuth2AuthorizedClientService)에
 * 쌓는다. ODO는 로그인 뒤 카카오 API를 부르지 않으므로 카카오 토큰이 필요 없고, 서버 메모리에 로그인 상태를 두지 않는다.
 */
public class NoOpOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
			Authentication principal, HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
	}

	@Override
	public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
	}

}
