package SeSAC.Dance_Assessment.Realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 제어 이벤트 발행. 팀·DM 트랜잭션이 <b>커밋된 뒤에</b> 부른다.
 *
 * <p>발행이 실패해도 예외를 던지지 않는다 — 원본은 DB이고, 서버마다 5초마다 최근 변경을 다시
 * 읽어 고친다({@link AccessRecheck}). 실패는 로그와 지표로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlEventPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatRealtimeMetrics metrics;

    public void revoked(long userId, long roomId) {
        publish(new ControlEvent(ControlEvent.REVOKED, userId, roomId));
    }

    public void granted(long userId, long roomId) {
        publish(new ControlEvent(ControlEvent.GRANTED, userId, roomId));
    }

    private void publish(ControlEvent event) {
        try {
            redis.convertAndSend(ChatDestinations.CONTROL_CHANNEL, objectMapper.writeValueAsString(event));
            metrics.controlPublished(event.type());
        } catch (Exception e) {
            metrics.controlPublishFailed();
            log.error("[제어 이벤트 발행 실패] 5초 재확인이 고친다 {} 원인={}", event, e.toString());
        }
    }
}
