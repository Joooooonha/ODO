package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.DanceAssessmentApplication;
import SeSAC.Dance_Assessment.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [14절 Redis 연결 실패 처리] Redis에 닿지 않으면 기동이 실패한다 → 배포 기동 확인 실패 → 자동 롤백.
 * 운영 프로파일에서 Redis 접속 값이 빠지면 기본값으로 뜨지 않고 실패한다.
 *
 * <p>Spring 테스트 컨텍스트가 아니라 앱을 직접 띄운다(실패해야 하므로). 명령줄 인수가 프로파일 설정보다
 * 우선한다.
 */
class RedisStartupTest {

    @Test
    @DisplayName("Redis에 연결할 수 없으면 기동하지 않는다(PING 실패)")
    void unreachableRedisStopsStartup() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> new SpringApplicationBuilder(DanceAssessmentApplication.class)
                .profiles("dev")
                .run("--server.port=0",
                        "--" + TestContainers.INJECT_REDIS + "=false",
                        "--spring.data.redis.host=127.0.0.1",
                        "--spring.data.redis.port=" + closedPort))
                .hasStackTraceContaining("Redis에 연결할 수 없어 기동을 멈춥니다");
    }

    @Test
    @DisplayName("운영 프로파일에서 REDIS_PASSWORD가 없으면 기본값 없이 기동이 실패한다")
    void prodRequiresRedisPassword() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(DanceAssessmentApplication.class)
                // 기본 설정 파일의 spring.profiles.active=dev를 명령줄로 덮어써 prod만 켠다
                .run("--spring.profiles.active=prod",
                        "--server.port=0",
                        "--" + TestContainers.INJECT_REDIS + "=false",
                        "--ai.server.url=http://127.0.0.1:8000",
                        "--JWT_SECRET=test-only-jwt-secret-for-prod-profile-test-0123456789",
                        "--KAKAO_CLIENT_ID=t", "--KAKAO_CLIENT_SECRET=t", "--NAVER_CLIENT_ID=t", "--NAVER_CLIENT_SECRET=t",
                        "--storage.type=local",
                        "--REDIS_HOST=127.0.0.1",
                        "--REDIS_PORT=6379"))
                .hasStackTraceContaining("REDIS_PASSWORD");
    }
}
