package SeSAC.Dance_Assessment.Realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 제어 채널을 듣는다(모든 서버가 켜질 때부터). 받은 이벤트를 이 서버의 그 사용자 세션들에 적용한다.
 *
 * <p><b>방 메시지와 같은 구독 연결·같은 단일 스레드로 받는다.</b> 그래서 같은 서버가 차례로 발행한
 * "REVOKED → 추방 안내 메시지"는 이 서버에서도 그 순서로 처리된다 — 추방 안내가 브로커로 넘어갈 때는
 * 이미 허용 방 캐시에서 빠져 있어, 추방된 사람은 자기 추방 안내를 받지 않는다(E2·D3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlChannelListener implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final ObjectMapper objectMapper;
    private final ChatAccessControl access;
    private final ChatRealtimeMetrics metrics;

    @PostConstruct
    void subscribe() {
        container.addMessageListener(this, new ChannelTopic(ChatDestinations.CONTROL_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ControlEvent event = objectMapper.readValue(message.getBody(), ControlEvent.class);
            metrics.controlReceived(event.type());
            int changed = switch (event.type()) {
                case ControlEvent.REVOKED -> access.revoke(event.userId(), event.roomId());
                case ControlEvent.GRANTED -> access.grant(event.userId(), event.roomId());
                default -> {
                    log.warn("[제어 이벤트] 알 수 없는 종류 {}", event);
                    yield 0;
                }
            };
            log.debug("[제어 이벤트] {} → 이 서버 세션 {}개에 적용", event, changed);
        } catch (Exception e) {
            log.error("[제어 이벤트 처리 실패] 5초 재확인이 고친다 body={}", new String(message.getBody()), e);
        }
    }
}
