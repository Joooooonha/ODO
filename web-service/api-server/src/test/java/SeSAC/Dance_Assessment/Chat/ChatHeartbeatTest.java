package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.RawStompClient;
import SeSAC.Dance_Assessment.Realtime.ChatRealtimeMetrics;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하트비트 무신호로 서버가 끊은 세션 수(4.5 측정 준비). 운영 값은 10초(무신호 30초에 끊음)인데, 테스트를
 * 기다리지 않게 0.5초(1.5초에 끊음)로 줄여 따로 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "chat.ws.heartbeat-ms=500")
class ChatHeartbeatTest {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    ChatRealtimeMetrics metrics;

    @Test
    @DisplayName("하트비트를 약속하고 보내지 않는 클라이언트는 서버가 끊고(heartbeat로 셈), 보내는 클라이언트는 유지된다")
    void silentClientIsDisconnected() throws Exception {
        LoggedIn me = new TestUsers(mvc, om).create("나");
        long before = metrics.disconnects(ChatRealtimeMetrics.HEARTBEAT);

        try (RawStompClient silent = RawStompClient.connect(port, me.cookie(), "500,0");
             RawStompClient alive = RawStompClient.connect(port, me.cookie(), "500,0")) {
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                alive.sendText("\n");          // STOMP 하트비트
                Thread.sleep(300);
            }
            assertThat(silent.expect("ERROR", Duration.ofSeconds(1)).headers().get("message")).isEqualTo("Session closed.");
            silent.awaitClosed(Duration.ofSeconds(5));
            assertThat(alive.isOpen()).isTrue();
        }
        assertThat(metrics.disconnects(ChatRealtimeMetrics.HEARTBEAT) - before).isEqualTo(1);
    }
}
