package SeSAC.Dance_Assessment.Security;

import SeSAC.Dance_Assessment.DanceAssessmentApplication;
import SeSAC.Dance_Assessment.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 프로필은 비밀값의 개발용 기본값을 쓰지 않는다. 빠지면 기동이 실패한다 → 배포 자동 롤백.
 * (저장소에 공개된 개발용 JWT 키로 뜨면 누구나 토큰을 위조할 수 있다.)
 *
 * <p>앱을 직접 띄운다(실패해야 하므로). 명령줄 인수가 설정 파일보다 우선한다.
 */
class ProdSecretsTest {

    private static List<String> prodArgs(String... without) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=prod",
                "--server.port=0",
                "--ai.server.url=http://127.0.0.1:8000",
                "--storage.type=local",
                "--REDIS_HOST=127.0.0.1", "--REDIS_PORT=6379", "--REDIS_PASSWORD=x",
                "--" + TestContainers.INJECT_REDIS + "=false",
                "--JWT_SECRET=test-only-jwt-secret-for-prod-profile-test-0123456789",
                "--KAKAO_CLIENT_ID=t", "--KAKAO_CLIENT_SECRET=t",
                "--NAVER_CLIENT_ID=t", "--NAVER_CLIENT_SECRET=t"));
        for (String name : without) {
            args.removeIf(a -> a.startsWith("--" + name + "="));
        }
        return args;
    }

    private static void start(List<String> args) {
        new SpringApplicationBuilder(DanceAssessmentApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    @DisplayName("운영 프로필에서 JWT_SECRET이 없으면 개발용 키로 뜨지 않고 기동이 실패한다")
    void missingJwtSecretFails() {
        assertThatThrownBy(() -> start(prodArgs("JWT_SECRET")))
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("운영 프로필에서 소셜 로그인 키가 없으면 dummy로 뜨지 않고 기동이 실패한다")
    void missingSocialLoginKeysFail() {
        assertThatThrownBy(() -> start(prodArgs("KAKAO_CLIENT_ID")))
                .hasStackTraceContaining("운영 필수 설정이 비어 있습니다: spring.security.oauth2.client.registration.kakao.client-id")
                .hasStackTraceContaining("KAKAO_CLIENT_ID");
        assertThatThrownBy(() -> start(prodArgs("NAVER_CLIENT_SECRET")))
                .hasStackTraceContaining("NAVER_CLIENT_SECRET");
    }
}
