package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;
import SeSAC.Dance_Assessment.Service.Chat.MessageSavedEvent;
import SeSAC.Dance_Assessment.Service.Chat.PublishDeferral;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
import SeSAC.Dance_Assessment.Service.Chat.SendStages;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 저장이 <b>커밋된 뒤</b> Redis 방 채널로 발행한다(5.2). 롤백된 메시지는 이 메서드가 불리지 않으므로
 * 구조적으로 방송되지 않는다.
 *
 * <p><b>발행이 실패해도 예외를 던지지 않는다.</b> 저장은 이미 끝났고 확정 응답은 나가야 한다. 유실은
 * 허용한다 — 원본은 DB이고 번호에 빈틈이 없어 브라우저가 감지해 채운다. 명령 시간 제한(500ms)과
 * 끊긴 동안 즉시 거절(RedisConfig) 때문에 Redis가 멈춰도 이 메서드가 처리 스레드를 오래 붙잡지 않는다.
 *
 * <p>{@code chat.send.publish-after-release}를 켜면 사람이 보낸 메시지는 커넥션을 돌려준 뒤 같은 스레드에서 발행한다
 * ({@link PublishDeferral}). 발행할 때 커넥션을 쥐고 있었는지는 {@code chat.redis.publish.connection{state}}로 센다.
 *
 * <p>클래스에도 메서드에도 {@code @Transactional}을 붙이지 않는다(6.1+ 제약).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBroadcaster {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatRealtimeMetrics metrics;
    private final JdbcTemplate jdbc;
    private final SendStages stages;
    private final PublishDeferral deferral;

    @TransactionalEventListener
    public void onSaved(MessageSavedEvent event) {
        stages.mark(SendStages.AFTER_COMMIT);
        if (deferral.defer(() -> publishMarked(event))) {
            return;
        }
        publishMarked(event);
    }

    private void publishMarked(MessageSavedEvent event) {
        try {
            publish(event);
        } finally {
            stages.mark(SendStages.PUBLISH);
        }
    }

    private void publish(MessageSavedEvent event) {
        metrics.publishConnection(TransactionSynchronizationManager.isActualTransactionActive());
        RoomBroadcast broadcast = RoomBroadcast.of(event.message(), targetNickname(event.message()));
        try {
            redis.convertAndSend(ChatDestinations.roomChannel(broadcast.roomId()).getTopic(),
                    objectMapper.writeValueAsString(broadcast));
            metrics.published(ChatRealtimeMetrics.MESSAGE);
        } catch (Exception e) {
            metrics.publishFailed();
            log.error("[Redis 발행 실패] 저장·확정은 정상, 실시간 전달만 빠짐(브라우저가 빈틈으로 채움) roomId={} seq={} 원인={}",
                    broadcast.roomId(), broadcast.seq(), e.toString());
        }
    }

    /** 시스템 메시지 대상의 지금 닉네임. 실패해도 방송은 한다(화면이 대체 문구를 쓴다). */
    private String targetNickname(SavedMessage message) {
        if (message.type() != ChatMessageType.SYSTEM) {
            return null;
        }
        try {
            long target = objectMapper.readTree(message.content()).path("targetUserId").asLong();
            return jdbc.query("SELECT nickname FROM users WHERE user_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, target);
        } catch (Exception e) {
            log.warn("[채팅 안내 닉네임 조회 실패] roomId={} seq={} 원인={}", message.roomId(), message.seq(), e.toString());
            return null;
        }
    }
}
