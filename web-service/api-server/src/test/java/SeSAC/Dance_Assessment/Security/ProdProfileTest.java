package SeSAC.Dance_Assessment.Security;

import SeSAC.Dance_Assessment.Common.ThreadCpuMetrics;
import SeSAC.Dance_Assessment.Config.LoadtestFaultEndpoint;
import SeSAC.Dance_Assessment.Service.Chat.ReadAggregator;
import SeSAC.Dance_Assessment.Service.Chat.ReadFaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 프로필(prod만, 10/21 병합 준비): 측정 전용 끝점·훅이 <b>등록되지 않고</b>, actuator는 아무것도 열리지 않는다.
 * 측정으로 채택한 기본값(메시지 처리 스레드 8개, 서버 쪽 모으기, 벌크헤드 32)이 켜져 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        // 테스트 전용 값. 운영 값이 아니다
        "JWT_SECRET=test-only-jwt-secret-for-prod-profile-test-0123456",
        "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
        "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test",
        "AI_SERVER_URL=http://127.0.0.1:8000",
        "R2_BUCKET=test", "R2_ENDPOINT=http://127.0.0.1:9", "R2_ACCESS_KEY=test", "R2_SECRET_KEY=test",
})
class ProdProfileTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    ReadAggregator aggregator;

    @Autowired
    @Qualifier("clientInboundChannelExecutor")
    Executor inbound;

    @Value("${chat.read.bulkhead.max-concurrent}")
    int bulkhead;

    @Value("${chat.metrics.send-stages}")
    boolean sendStages;

    @Value("${chat.metrics.read-agg-slow}")
    boolean readAggSlow;

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b = method.equals("POST")
                ? b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"read-fault\",\"seconds\":10}"))
                : b.GET();
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("actuator·측정 전용 경로는 로그인 없이 열리지 않는다(401 또는 404) — 지표·상태를 밖에서 볼 수 없다")
    void actuatorAndLoadtestPathsAreClosed() throws Exception {
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/prometheus", "/actuator/metrics",
                "/actuator/env", "/actuator/loadtest")) {
            assertThat(send("GET", path).statusCode()).as(path).isIn(401, 404);
        }
        assertThat(send("POST", "/actuator/loadtest").statusCode()).isIn(401, 403, 404);
    }

    @Test
    @DisplayName("측정 전용 빈(장애 주입 끝점·ReadFaults·스레드별 CPU 지표)은 만들어지지 않는다")
    void measurementBeansAreNotRegistered() {
        assertThat(context.getBeanNamesForType(LoadtestFaultEndpoint.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReadFaults.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ThreadCpuMetrics.class)).isEmpty();
        assertThat(sendStages).isFalse();
        assertThat(readAggSlow).isFalse();
    }

    @Test
    @DisplayName("채택한 운영 기본값: 메시지 처리 스레드 8개, 서버 쪽 모으기 켬(서버 한 대 → 위상 0), 벌크헤드 32")
    void adoptedDefaults() {
        assertThat(((ThreadPoolTaskExecutor) inbound).getCorePoolSize()).isEqualTo(8);
        assertThat(aggregator.enabled()).isTrue();
        assertThat(bulkhead).isEqualTo(32);
    }
}
