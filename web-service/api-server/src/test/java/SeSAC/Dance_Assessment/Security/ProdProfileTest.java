package SeSAC.Dance_Assessment.Security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 운영 프로파일에서 개발용 경로가 실제로 사라지는지 확인한다.
 *
 * <p>"운영에서는 비활성"이라고 <b>말만 하고 확인하지 않으면</b> 그게 곧 구멍이 된다.
 * 아이디/비밀번호 로그인과 시드 계정은 개발 편의를 위한 것이고, 운영에 남으면
 * 비밀번호가 저장되는 경로가 되살아난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
// 운영 설정 중 **인프라에 의존하는 항목만** 테스트용으로 덮어쓴다.
// 이 테스트가 확인하려는 것은 "프로파일로 꺼지는 빈"이지 DB/네트워크가 아니다.
//
// - DB는 덮어쓰지 않는다. 운영과 같이 Flyway + validate로 뜬다(테스트 DB는 PostgreSQL).
//   그래서 이 테스트는 "운영 설정으로 마이그레이션과 엔티티가 맞는지"도 함께 확인한다.
//   H2 시절에는 ddl-auto=create-drop으로 덮어썼는데, 공유 PostgreSQL에서 그러면
//   Flyway가 만든 테이블을 전부 지우고 엔티티 기준으로 다시 만들어 버려서(부분 인덱스·
//   CHECK가 사라짐) 뒤에 도는 테스트가 엉뚱한 스키마에서 통과했다.
// - ai.server.url: 운영에서는 값이 없으면 **즉시 실패하는 편이 옳다**
//   (맥미니 주소를 빠뜨린 채 뜨면 분석이 전부 실패한다). 그래서 기본값을 두지
//   않았고, 대신 테스트에서만 채운다.
// - 비밀값: 운영 프로필은 기본값이 없다(ProdSecretsCheck). 테스트용 값을 환경 변수 이름으로 채운다.
@TestPropertySource(properties = {
        "JWT_SECRET=test-only-jwt-secret-for-prod-profile-test-0123456789",
        "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
        "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test",
        "ai.server.url=http://127.0.0.1:8000",
        // 저장소도 이 테스트의 관심사가 아니다. 운영은 r2지만 자격증명이 없으면
        // 뜨지 않는 것이 맞고(빠뜨린 채 배포되면 안 되므로), 여기서는 local로 둔다.
        "storage.type=local",
})
class ProdProfileTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("[핵심] 운영에서는 로컬 로그인 엔드포인트가 존재하지 않는다")
    void localLoginIsGoneInProd() throws Exception {
        int status = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("loginId", "test", "password", "test1234"))))
                .andReturn().getResponse().getStatus();

        // 401(인증 필요)이 아니라 404(그런 경로 없음)여야 한다.
        // 401이면 엔드포인트는 살아 있고 인증만 막힌 것이다.
        assertThat(status).isEqualTo(404);
    }

    @Test
    @DisplayName("[핵심] 운영에서는 회원가입 엔드포인트도 없다")
    void localSignupIsGoneInProd() throws Exception {
        int status = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("loginId", "x", "password", "y", "nickname", "z"))))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    @DisplayName("[핵심] 운영에서는 시드 계정 생성기가 아예 로드되지 않는다")
    void seedComponentIsNotLoadedInProd() {
        // DB 내용이 아니라 **빈 존재 여부**를 본다.
        //
        // 처음에는 `userRepository.count()`가 0인지 봤는데 실패했다. 원인은
        // InitDb가 아니라 테스트 환경이었다 — `jdbc:h2:mem:testdb`는 이름이 같으면
        // 같은 JVM 안에서 **같은 DB를 공유**한다. dev 프로파일 테스트가 만든 시드
        // 계정이 prod 컨텍스트에도 그대로 보였던 것이다.
        // 그 검사로는 "InitDb가 안 돌았다"와 "다른 테스트 데이터가 섞였다"를
        // 구분할 수 없으므로, 의도를 직접 확인하는 쪽으로 바꿨다.
        assertThat(context.getBeanNamesForType(SeSAC.Dance_Assessment.InitDb.class))
                .as("운영에는 비밀번호가 달린 시드 계정이 만들어지면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("운영에서도 로그아웃은 동작한다 (프로파일 무관)")
    void logoutStillWorks() throws Exception {
        int status = mvc.perform(post("/api/auth/logout"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(204);
    }

    @Test
    @DisplayName("[read 5절] 운영에서는 Prometheus 레지스트리가 만들어지지 않는다(jar에는 들어 있지만 기본 꺼짐)")
    void prometheusIsOffInProd() {
        assertThat(context.getBeanNamesForType(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class)).isEmpty();
    }
}
