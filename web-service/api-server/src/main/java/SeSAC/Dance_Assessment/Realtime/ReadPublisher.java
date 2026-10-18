package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ReadSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 읽음 스냅샷을 Redis 방 채널({@code chat:room:{id}}, 메시지와 같은 채널)로 발행한다. 받는 서버가 구간을
 * 계산해 그 방 구독자 <b>전원</b>에게 보낸다(걸러 보내지 않는다 — 기준선).
 *
 * <p><b>발행이 실패해도 예외를 던지지 않는다.</b> 읽음 위치는 이미 저장됐다. 유실돼도 다음 스냅샷이
 * 전체 상태를 다시 실어 와 복구된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatRealtimeMetrics metrics;

    public void publish(ReadSnapshot snapshot) {
        try {
            redis.convertAndSend(ChatDestinations.roomChannel(snapshot.roomId()).getTopic(),
                    objectMapper.writeValueAsString(ReadFrame.published(snapshot)));
            metrics.published(ChatRealtimeMetrics.READ);
        } catch (Exception e) {
            metrics.publishFailed();
            log.error("[Redis 읽음 발행 실패] 읽음 위치는 저장됨, 다음 스냅샷이 복구 roomId={} version={} 원인={}",
                    snapshot.roomId(), snapshot.version(), e.toString());
        }
    }
}
