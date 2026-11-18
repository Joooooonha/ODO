package com.odo.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.odo.common.crypto.SignedCookieCodec;
import com.odo.support.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import static com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME;
import static com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository.REDIRECT_ATTRIBUTE;
import static com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository.REDIRECT_PARAMETER;
import static org.assertj.core.api.Assertions.assertThat;

class CookieOAuth2AuthorizationRequestRepositoryTest {

	private static final byte[] SECRET = "oauth-cookie-secret-for-unit-test-0123456789".getBytes(StandardCharsets.UTF_8);

	private static final byte[] OTHER_SECRET = "another-secret-for-unit-test-0123456789abcd".getBytes(StandardCharsets.UTF_8);

	private MutableClock clock;

	/** 카카오로 보내는 요청을 처리한 서버 */
	private CookieOAuth2AuthorizationRequestRepository serverA;

	/** 카카오에서 돌아온 요청을 받은 다른 서버. 같은 비밀키를 쓰고 메모리는 공유하지 않는다 */
	private CookieOAuth2AuthorizationRequestRepository serverB;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		serverA = new CookieOAuth2AuthorizationRequestRepository(new SignedCookieCodec(SECRET, clock));
		serverB = new CookieOAuth2AuthorizationRequestRepository(new SignedCookieCodec(SECRET, clock));
	}

	@Test
	@DisplayName("한 서버가 저장한 인가 요청을 다른 서버가 쿠키로 읽는다")
	void loadOnAnotherServer() {
		OAuth2AuthorizationRequest saved = authorizationRequest("state-123");
		String cookie = save(serverA, saved, "/invite/abc");

		OAuth2AuthorizationRequest loaded = serverB.loadAuthorizationRequest(requestWithCookie(cookie));

		assertThat(loaded).isNotNull();
		assertThat(loaded.getState()).isEqualTo("state-123");
		assertThat(loaded.getAuthorizationUri()).isEqualTo(saved.getAuthorizationUri());
		assertThat(loaded.getClientId()).isEqualTo(saved.getClientId());
		assertThat(loaded.getRedirectUri()).isEqualTo(saved.getRedirectUri());
		assertThat(loaded.getScopes()).isEqualTo(saved.getScopes());
		assertThat(loaded.getAuthorizationRequestUri()).isEqualTo(saved.getAuthorizationRequestUri());
		assertThat(loaded.<String>getAttribute(OAuth2ParameterNames.REGISTRATION_ID)).isEqualTo("kakao");
	}

	@Test
	@DisplayName("저장할 때 쿠키 속성은 HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=180이다")
	void cookieAttributes() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		serverA.saveAuthorizationRequest(authorizationRequest("s"), startRequest("/invite/abc"), response);

		String setCookie = setCookieHeader(response);
		assertThat(setCookie).startsWith(COOKIE_NAME + "=");
		assertThat(setCookie).containsIgnoringCase("HttpOnly");
		assertThat(setCookie).containsIgnoringCase("Secure");
		assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
		assertThat(setCookie).contains("Path=/");
		assertThat(setCookie).contains("Max-Age=180");
	}

	@Test
	@DisplayName("저장해도 서버 세션을 만들지 않는다")
	void noSession() {
		MockHttpServletRequest request = startRequest("/invite/abc");
		serverA.saveAuthorizationRequest(authorizationRequest("s"), request, new MockHttpServletResponse());

		assertThat(request.getSession(false)).isNull();
	}

	@Test
	@DisplayName("쿠키 값을 한 글자라도 바꾸면 읽히지 않는다")
	void tampered() {
		String cookie = save(serverA, authorizationRequest("s"), "/invite/abc");

		int dot = cookie.indexOf('.');
		// base64 끝 글자는 쓰지 않는 비트가 있어 바꿔도 같은 값이 될 수 있으므로 앞과 가운데를 바꾼다
		for (int index : List.of(0, dot / 2, dot + 1, dot + (cookie.length() - dot) / 2)) {
			String tampered = flipCharAt(cookie, index);
			assertThat(serverB.loadAuthorizationRequest(requestWithCookie(tampered))).as("index %d", index).isNull();
		}

		String signatureRemoved = cookie.substring(0, dot + 1);
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie(signatureRemoved))).isNull();
	}

	@Test
	@DisplayName("다른 비밀키로 서명한 쿠키는 읽히지 않는다")
	void otherSecret() {
		var attacker = new CookieOAuth2AuthorizationRequestRepository(new SignedCookieCodec(OTHER_SECRET, clock));
		String cookie = save(attacker, authorizationRequest("s"), "/invite/abc");

		assertThat(serverB.loadAuthorizationRequest(requestWithCookie(cookie))).isNull();
	}

	@Test
	@DisplayName("180초가 지난 쿠키는 읽히지 않는다")
	void expired() {
		String cookie = save(serverA, authorizationRequest("s"), "/invite/abc");

		clock.advance(Duration.ofSeconds(180));
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie(cookie))).isNotNull();

		clock.advance(Duration.ofSeconds(1));
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie(cookie))).isNull();
	}

	@Test
	@DisplayName("쿠키가 없거나 형식이 틀리면 읽히지 않는다")
	void missingOrMalformed() {
		assertThat(serverB.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie(""))).isNull();
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie("not-a-signed-value"))).isNull();
		assertThat(serverB.loadAuthorizationRequest(requestWithCookie("a.b.c"))).isNull();
	}

	@Test
	@DisplayName("지우면 인가 요청을 돌려주고, 쿠키를 지우고, 돌아갈 주소를 요청 속성에 남긴다")
	void remove() {
		String cookie = save(serverA, authorizationRequest("state-123"), "/invite/abc");
		MockHttpServletRequest callback = requestWithCookie(cookie);
		MockHttpServletResponse response = new MockHttpServletResponse();

		OAuth2AuthorizationRequest removed = serverB.removeAuthorizationRequest(callback, response);

		assertThat(removed).isNotNull();
		assertThat(removed.getState()).isEqualTo("state-123");
		assertThat(callback.getAttribute(REDIRECT_ATTRIBUTE)).isEqualTo("/invite/abc");
		String setCookie = setCookieHeader(response);
		assertThat(setCookie).startsWith(COOKIE_NAME + "=");
		assertThat(setCookie).contains("Max-Age=0");
	}

	@Test
	@DisplayName("로그인 시작 때 받은 돌아갈 주소가 허용되지 않으면 / 로 남긴다")
	void invalidRedirect() {
		String cookie = save(serverA, authorizationRequest("s"), "https://evil.com");
		MockHttpServletRequest callback = requestWithCookie(cookie);

		serverB.removeAuthorizationRequest(callback, new MockHttpServletResponse());

		assertThat(callback.getAttribute(REDIRECT_ATTRIBUTE)).isEqualTo("/");
	}

	@Test
	@DisplayName("로그인 시작 때 돌아갈 주소가 없으면 / 로 남긴다")
	void noRedirect() {
		String cookie = save(serverA, authorizationRequest("s"), null);
		MockHttpServletRequest callback = requestWithCookie(cookie);

		serverB.removeAuthorizationRequest(callback, new MockHttpServletResponse());

		assertThat(callback.getAttribute(REDIRECT_ATTRIBUTE)).isEqualTo("/");
	}

	@Test
	@DisplayName("쿠키가 없을 때 지우면 null을 돌려준다")
	void removeWithoutCookie() {
		assertThat(serverB.removeAuthorizationRequest(new MockHttpServletRequest(), new MockHttpServletResponse()))
			.isNull();
	}

	@Test
	@DisplayName("null을 저장하면 쿠키를 지운다")
	void saveNullDeletesCookie() {
		MockHttpServletResponse response = new MockHttpServletResponse();

		serverA.saveAuthorizationRequest(null, startRequest(null), response);

		String setCookie = setCookieHeader(response);
		assertThat(setCookie).startsWith(COOKIE_NAME + "=");
		assertThat(setCookie).contains("Max-Age=0");
	}

	private static OAuth2AuthorizationRequest authorizationRequest(String state) {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.clientId("test-kakao-client-id")
			.redirectUri("http://localhost:8080/login/oauth2/code/kakao")
			.scopes(Set.of("profile_nickname"))
			.state(state)
			.attributes(attributes -> attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "kakao"))
			.build();
	}

	private static MockHttpServletRequest startRequest(String redirect) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
		if (redirect != null) {
			request.setParameter(REDIRECT_PARAMETER, redirect);
		}
		return request;
	}

	private static String save(CookieOAuth2AuthorizationRequestRepository repository,
			OAuth2AuthorizationRequest authorizationRequest, String redirect) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(authorizationRequest, startRequest(redirect), response);
		return cookieValue(setCookieHeader(response));
	}

	private static MockHttpServletRequest requestWithCookie(String value) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
		request.setCookies(new Cookie(COOKIE_NAME, value));
		return request;
	}

	private static String setCookieHeader(MockHttpServletResponse response) {
		return response.getHeaders("Set-Cookie")
			.stream()
			.filter(header -> header.startsWith(COOKIE_NAME + "="))
			.findFirst()
			.orElseThrow(() -> new AssertionError(COOKIE_NAME + " Set-Cookie 헤더가 없다"));
	}

	private static String cookieValue(String setCookieHeader) {
		String nameValue = setCookieHeader.split(";", 2)[0];
		return nameValue.substring(nameValue.indexOf('=') + 1);
	}

	private static String flipCharAt(String value, int index) {
		char original = value.charAt(index);
		char replacement = original == 'A' ? 'B' : 'A';
		return value.substring(0, index) + replacement + value.substring(index + 1);
	}

}
