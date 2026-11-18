package com.odo.auth.infrastructure.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpOAuth2AuthorizedClientRepositoryTest {

	@Test
	@DisplayName("카카오 토큰을 저장해도 남지 않아 다시 읽으면 null이다")
	void savesNothing() {
		ClientRegistration kakao = ClientRegistration.withRegistrationId("kakao")
			.clientId("client")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("http://localhost:8080/login/oauth2/code/kakao")
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.tokenUri("https://kauth.kakao.com/oauth/token")
			.build();
		Instant now = Instant.parse("2026-11-18T00:00:00Z");
		OAuth2AccessToken kakaoToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "kakao-token", now,
				now.plusSeconds(3600));
		OAuth2AuthenticationToken principal = new OAuth2AuthenticationToken(
				new DefaultOAuth2User(List.of(), Map.of("id", 123L), "id"), List.of(), "kakao");
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		NoOpOAuth2AuthorizedClientRepository repository = new NoOpOAuth2AuthorizedClientRepository();

		repository.saveAuthorizedClient(new OAuth2AuthorizedClient(kakao, "123", kakaoToken), principal, request,
				response);

		OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient("kakao", principal, request);
		assertThat(loaded).isNull();
		assertThat(request.getSession(false)).isNull();
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
	}

}
