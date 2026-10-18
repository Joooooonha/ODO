package SeSAC.Dance_Assessment.Security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 프로필(prod,loadtest, Task-chat-read 5·7절): 운영 설정 위에 덮어 뜨고, Prometheus는 <b>관리 포트에서만</b>
 * 보인다. 앱 포트에는 actuator가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "loadtest"})
@TestPropertySource(properties = {
        // 측정 전용 값(테스트). 운영 값이 아니다
        "JWT_SECRET=test-only-jwt-secret-for-loadtest-profile-test-0123",
        "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
        "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test",
        "ai.server.url=http://127.0.0.1:8000",
        "CORS_ALLOWED_ORIGINS=http://localhost:5173",
        "management.server.port=0",
})
class LoadtestProfileTest {

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @Value("${chat.metrics.log-interval}")
    String logInterval;

    @Value("${app.cookie.secure}")
    boolean cookieSecure;

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("관리 포트의 /actuator/prometheus가 채팅·읽음 지표를 내보낸다")
    void prometheusOnManagementPort() throws Exception {
        HttpResponse<String> res = get(managementPort, "/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("chat_redis_dispatch_wait_seconds").contains("hikaricp_connections");
    }

    @Test
    @DisplayName("앱 포트에는 actuator가 없다(밖으로 여는 nginx는 앱 포트만 넘긴다)")
    void noActuatorOnAppPort() throws Exception {
        assertThat(get(appPort, "/actuator/prometheus").statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("측정 프로필 값: 요약 10초, 쿠키 Secure 끔(LAN이 http)")
    void overrides() {
        assertThat(logInterval).isEqualTo("10s");
        assertThat(cookieSecure).isFalse();
    }
}
