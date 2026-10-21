package SeSAC.Dance_Assessment.Security;

import SeSAC.Dance_Assessment.Config.LoadtestFaultEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 모니터링 프로필(prod,monitoring, 10/21): 관리 포트에서만 prometheus·health를 로그인 없이 내보낸다.
 * 앱 포트(nginx가 밖으로 넘기는 포트)에는 actuator가 없고, 측정 전용 장애 주입은 어디에도 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "monitoring"})
@TestPropertySource(properties = {
        // 테스트 전용 값. 운영 값이 아니다
        "JWT_SECRET=test-only-jwt-secret-for-monitoring-profile-test-01",
        "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
        "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test",
        "ai.server.url=http://127.0.0.1:8000",
        "storage.type=local",
        "management.server.port=0",
})
class MonitoringProfileTest {

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ApplicationContext context;

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("관리 포트: prometheus는 로그인 없이 200(채팅 지표 포함), health는 상태만(세부 없음)")
    void managementPortServesMetrics() throws Exception {
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("hikaricp_connections").contains("chat_redis_dispatch_wait_seconds");
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\"").doesNotContain("components");
    }

    @Test
    @DisplayName("관리 포트에도 prometheus·health 말고는 없다(env·metrics·loadtest), 앱 포트에는 actuator가 아예 없다")
    void nothingElseIsOpen() throws Exception {
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/loadtest", "/actuator/beans")) {
            assertThat(get(managementPort, path).statusCode()).as("관리 포트 " + path).isIn(401, 404);
        }
        for (String path : List.of("/actuator", "/actuator/prometheus", "/actuator/health")) {
            assertThat(get(appPort, path).statusCode()).as("앱 포트 " + path).isIn(401, 404);
        }
        assertThat(context.getBeanNamesForType(LoadtestFaultEndpoint.class)).isEmpty();
    }
}
