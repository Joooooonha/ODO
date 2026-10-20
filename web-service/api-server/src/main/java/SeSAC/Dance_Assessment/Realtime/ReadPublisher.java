package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ReadSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 여러 방을 한 번에(10/21 서버 쪽 모으기 개선): PUBLISH들을 한 연결에 파이프라인으로 보내고 응답을 한꺼번에 받는다.
     * 방마다 왕복을 기다리지 않는다. 같은 연결이라 보낸 순서(방 번호 순)대로 Redis에 들어간다.
     * 실패하면 이번 묶음 전체를 실패로 센다(읽음 위치는 이미 저장됐고, 다음 스냅샷이 복구한다 — {@link #publish}와 같다)
     */
    public void publishAll(List<ReadSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        List<byte[][]> frames = new ArrayList<>(snapshots.size());
        for (ReadSnapshot snapshot : snapshots) {
            try {
                frames.add(new byte[][]{
                        ChatDestinations.roomChannel(snapshot.roomId()).getTopic().getBytes(StandardCharsets.UTF_8),
                        objectMapper.writeValueAsBytes(ReadFrame.published(snapshot))});
            } catch (Exception e) {
                metrics.publishFailed();
                log.error("[Redis 읽음 발행 실패] 직렬화 roomId={} 원인={}", snapshot.roomId(), e.toString());
            }
        }
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (byte[][] f : frames) {
                    publish(connection, f[0], f[1]);
                }
                return null;
            });
            for (int i = 0; i < frames.size(); i++) {
                metrics.published(ChatRealtimeMetrics.READ);
            }
        } catch (Exception e) {
            for (int i = 0; i < frames.size(); i++) {
                metrics.publishFailed();
            }
            log.error("[Redis 읽음 발행 실패] 묶음 {}건, 읽음 위치는 저장됨, 다음 스냅샷이 복구 원인={}", frames.size(), e.toString());
        }
    }

    private static void publish(RedisConnection connection, byte[] channel, byte[] message) {
        connection.publish(channel, message);
    }
}
