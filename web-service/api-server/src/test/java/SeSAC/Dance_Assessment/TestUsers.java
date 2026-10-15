package SeSAC.Dance_Assessment;

import SeSAC.Dance_Assessment.Security.JwtCookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 테스트용 사용자를 dev 회원가입 API로 만들고 로그인 쿠키를 받는다.
 *
 * <p>테스트 컨텍스트끼리 H2 메모리 DB(jdbc:h2:mem:testdb)를 공유하므로
 * 아이디는 매번 새로 만든다. 시드 계정(test, test2)을 쓰면 다른 테스트가 만든
 * 팀·영상과 섞인다.
 */
public final class TestUsers {

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;

    public TestUsers(MockMvc mvc, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
    }

    public record LoggedIn(Long id, String nickname, Cookie cookie) {
    }

    public LoggedIn create(String nickname) throws Exception {
        String loginId = "t_" + UUID.randomUUID().toString().substring(0, 12);
        String body = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId, "password", "pw1234", "nickname", nickname))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(body).get("id").asLong();

        Cookie cookie = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId, "password", "pw1234"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtCookie.NAME);
        return new LoggedIn(id, nickname, cookie);
    }
}
