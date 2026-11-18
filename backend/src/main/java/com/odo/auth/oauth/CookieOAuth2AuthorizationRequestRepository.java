package com.odo.auth.oauth;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.odo.common.SignedCookieCodec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오로 보낼 때 만든 인가 요청(state 포함)과 돌아갈 주소를 서버 세션 대신 서명된 쿠키에 둔다.
 * 카카오에서 돌아오는 요청이 다른 서버에 닿아도 쿠키가 같이 오므로 어느 서버든 확인할 수 있다.
 * <p>
 * 쿠키 본문은 JSON이다. 사용자가 보낸 값을 Java 역직렬화하면 원격 코드 실행 취약점이 될 수 있어 쓰지 않는다.
 * 서명은 SignedCookieCodec이 먼저 확인하고, 맞을 때만 JSON을 읽는다.
 */
public class CookieOAuth2AuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	public static final String COOKIE_NAME = "ODO_OAUTH";

	/** 로그인 시작 요청에서 돌아갈 주소를 받는 파라미터. */
	public static final String REDIRECT_PARAMETER = "redirect";

	/** 인가 요청을 지울 때 돌아갈 주소를 담아 두는 요청 속성. 로그인 성공 처리가 읽는다. */
	public static final String REDIRECT_ATTRIBUTE = CookieOAuth2AuthorizationRequestRepository.class.getName() + ".REDIRECT";

	private static final Duration TTL = Duration.ofSeconds(180);

	private static final Logger log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SignedCookieCodec codec;

	public CookieOAuth2AuthorizationRequestRepository(SignedCookieCodec codec) {
		this.codec = codec;
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return readStored(request).map(StoredRequest::toAuthorizationRequest).orElse(null);
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
			HttpServletResponse response) {
		if (authorizationRequest == null) {
			deleteCookie(response);
			return;
		}
		String redirect = RedirectPathValidator.sanitize(request.getParameter(REDIRECT_PARAMETER));
		String json = JSON.writeValueAsString(StoredRequest.from(authorizationRequest, redirect));
		ResponseCookie cookie = baseCookie(codec.encode(json, TTL)).maxAge(TTL).build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		Optional<StoredRequest> stored = readStored(request);
		if (stored.isEmpty()) {
			return null;
		}
		deleteCookie(response);
		request.setAttribute(REDIRECT_ATTRIBUTE, stored.get().redirect());
		return stored.get().toAuthorizationRequest();
	}

	private Optional<StoredRequest> readStored(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		return Arrays.stream(cookies)
			.filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
			.findFirst()
			.flatMap(cookie -> codec.decode(cookie.getValue()))
			.flatMap(CookieOAuth2AuthorizationRequestRepository::parse);
	}

	private static Optional<StoredRequest> parse(String json) {
		try {
			return Optional.of(JSON.readValue(json, StoredRequest.class));
		}
		catch (JacksonException | IllegalArgumentException ex) {
			// 서명은 맞았는데 내용을 읽을 수 없다. 형식이 바뀐 배포 직후 같은 경우다
			log.warn("OAuth 상태 쿠키의 내용을 읽을 수 없다", ex);
			return Optional.empty();
		}
	}

	private static void deleteCookie(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("").maxAge(0).build().toString());
	}

	/** 카카오에서 넘어오는 요청에 실려야 하므로 SameSite=Lax. Strict면 실리지 않는다. */
	private static ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(true).sameSite("Lax").path("/");
	}

	/** 쿠키에 담는 인가 요청. Spring Security가 콜백에서 다시 만들 수 있도록 필요한 값을 모두 둔다. */
	record StoredRequest(String authorizationUri, String clientId, String redirectUri, Set<String> scopes,
			String state, Map<String, Object> additionalParameters, Map<String, Object> attributes,
			String authorizationRequestUri, String redirect) {

		static StoredRequest from(OAuth2AuthorizationRequest request, String redirect) {
			return new StoredRequest(request.getAuthorizationUri(), request.getClientId(), request.getRedirectUri(),
					request.getScopes(), request.getState(), request.getAdditionalParameters(),
					request.getAttributes(), request.getAuthorizationRequestUri(), redirect);
		}

		OAuth2AuthorizationRequest toAuthorizationRequest() {
			return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri(authorizationUri)
				.clientId(clientId)
				.redirectUri(redirectUri)
				.scopes(scopes)
				.state(state)
				.additionalParameters(additionalParameters)
				.attributes(attributes)
				.authorizationRequestUri(authorizationRequestUri)
				.build();
		}

	}

}
