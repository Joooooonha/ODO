package SeSAC.Dance_Assessment.Security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증이 실제로 동작하는지 확인한다.
 *
 * <p>가장 중요한 것은 <b>예전 공격이 이제 막히는지</b>다. 이 서비스는 얼마 전까지
 * {@code X-User-Id} 헤더 하나로 사용자를 판별해서, 헤더 숫자만 바꾸면 남의 연습
 * 기록과 영상을 볼 수 있었다. 그 경로가 확실히 닫혔는지를 테스트로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthenticationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    private Cookie loginAndGetCookie() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("loginId", "test", "password", "test1234"))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        Cookie cookie = res.getCookie(JwtCookie.NAME);
        assertThat(cookie).as("로그인하면 JWT 쿠키가 내려와야 한다").isNotNull();
        return cookie;
    }

    @Test
    @DisplayName("[핵심] X-User-Id 헤더로는 더 이상 인증되지 않는다")
    void headerSpoofingIsRejected() throws Exception {
        // 예전에는 이 요청 하나로 1번 사용자의 기록이 그대로 나왔다.
        mvc.perform(get("/api/practice-logs").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/practice-logs/1").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[핵심] ?userId= 쿼리로 남의 영상을 볼 수 없다")
    void videoQueryParamAuthIsGone() throws Exception {
        // 예전 영상 재생 API는 URL의 숫자만 바꾸면 남의 영상이 재생됐다.
        mvc.perform(get("/api/videos/1").param("userId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 없이 보호된 API에 접근하면 401")
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/practice-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("팀 API는 로그인하지 않으면 모두 401")
    void teamApisRequireLogin() throws Exception {
        mvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/join-preview")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/join")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teams/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teams/1/members")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teams/1/join-code")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/1/leave")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/1/members/2/kick")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("숙제 API는 제거되었다 — 로그인해도 404")
    void assignmentApiIsGone() throws Exception {
        Cookie cookie = loginAndGetCookie();

        mvc.perform(get("/api/assignments").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get("/api/assignments/1").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(post("/api/assignments").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그인 쿠키가 있으면 통과한다")
    void validCookieIsAccepted() throws Exception {
        Cookie cookie = loginAndGetCookie();

        mvc.perform(get("/api/practice-logs").cookie(cookie))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("JWT 쿠키는 httpOnly라 스크립트가 읽을 수 없다")
    void cookieIsHttpOnly() throws Exception {
        Cookie cookie = loginAndGetCookie();

        // localStorage에 토큰을 두면 XSS 한 번에 탈취되므로 쿠키로 옮겼다.
        // httpOnly가 빠지면 그 이점이 사라진다.
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("위조된 토큰은 거부된다")
    void forgedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/practice-logs")
                        .cookie(new Cookie(JwtCookie.NAME, "not-a-real-token")))
                .andExpect(status().isUnauthorized());

        // 서명만 살짝 바꾼 경우도 통과하면 안 된다
        Cookie valid = loginAndGetCookie();
        String tampered = valid.getValue().substring(0, valid.getValue().length() - 2) + "xx";
        mvc.perform(get("/api/practice-logs").cookie(new Cookie(JwtCookie.NAME, tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("틀린 비밀번호는 아이디 존재 여부를 알려주지 않는다")
    void loginFailureDoesNotLeakAccountExistence() throws Exception {
        String wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("loginId", "test", "password", "wrong"))))
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("loginId", "nobody", "password", "wrong"))))
                .andReturn().getResponse().getContentAsString();

        // 메시지가 다르면 어떤 아이디가 존재하는지 확인해볼 수 있게 된다
        assertThat(wrongPassword).isEqualTo(noSuchUser);
    }

    @Test
    @DisplayName("로그아웃하면 쿠키가 만료된다")
    void logoutClearsCookie() throws Exception {
        Cookie cookie = loginAndGetCookie();

        Cookie cleared = mvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie(JwtCookie.NAME);

        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }
}
