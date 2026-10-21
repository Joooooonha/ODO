package SeSAC.Dance_Assessment.Security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 소셜 로그인 인가 요청을 세션 대신 서명한 쿠키에 둔다(10/21 분산 구성). 로그인 시작과 콜백이 다른 서버로 가도
 * 콜백 서버가 인가 요청을 찾는지, 조작한 쿠키는 버리는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OAuth2CookieRequestTest {

    @Autowired
    MockMvc mvc;

    @Value("${jwt.secret}")
    String jwtSecret;

    private Cookie startLogin() throws Exception {
        MvcResult res = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(302);
        assertThat(res.getResponse().getRedirectedUrl()).startsWith("https://kauth.kakao.com/");
        // 서버 세션을 만들지 않는다(두 대 중 어느 서버로 콜백이 와도 되게)
        assertThat(res.getRequest().getSession(false)).isNull();
        Cookie cookie = res.getResponse().getCookie(CookieOAuth2AuthorizationRequestRepository.NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(180);
        // 브라우저 쿠키 한도(이름+값 4,096바이트) 안이어야 한다. 넘으면 브라우저가 조용히 버려 로그인이 실패한다
        assertThat(cookie.getName().length() + cookie.getValue().length()).isLessThan(4000);
        return cookie;
    }

    @Test
    @DisplayName("로그인 시작은 세션 없이 서명한 쿠키에 인가 요청을 담고, 다른 서버(같은 비밀값의 다른 인스턴스)가 그 쿠키로 같은 state를 찾는다")
    void otherServerFindsRequestFromCookie() throws Exception {
        MvcResult res = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
        Cookie cookie = res.getResponse().getCookie(CookieOAuth2AuthorizationRequestRepository.NAME);
        assertThat(cookie).isNotNull();
        String state = java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(res.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state"), java.nio.charset.StandardCharsets.UTF_8);

        // "다른 서버": 같은 비밀값으로 새로 만든 저장소
        CookieOAuth2AuthorizationRequestRepository other = new CookieOAuth2AuthorizationRequestRepository(false, jwtSecret);
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        callback.setCookies(cookie);
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest found = other.removeAuthorizationRequest(callback, response);
        assertThat(found).isNotNull();
        assertThat(found.getState()).isEqualTo(state);
        // 꺼내면 지운다(한 번만 쓴다)
        assertThat(response.getCookie(CookieOAuth2AuthorizationRequestRepository.NAME).getMaxAge()).isZero();
        startLogin();
    }

    @Test
    @DisplayName("조작한 쿠키(서명 불일치)와 다른 비밀값으로 서명한 쿠키는 버린다 — 역직렬화하지 않는다")
    void tamperedOrForeignCookieIsRejected() throws Exception {
        Cookie cookie = startLogin();
        String value = cookie.getValue();
        int dot = value.indexOf('.');
        char last = value.charAt(dot - 1);
        String tampered = value.substring(0, dot - 1) + (last == 'A' ? 'B' : 'A') + value.substring(dot);

        CookieOAuth2AuthorizationRequestRepository same = new CookieOAuth2AuthorizationRequestRepository(false, jwtSecret);
        assertThat(same.decode(tampered)).isNull();
        assertThat(same.decode("garbage")).isNull();
        assertThat(same.decode(value)).isNotNull();

        CookieOAuth2AuthorizationRequestRepository foreign = new CookieOAuth2AuthorizationRequestRepository(false, jwtSecret + "-other");
        assertThat(foreign.decode(value)).isNull();
    }
}
