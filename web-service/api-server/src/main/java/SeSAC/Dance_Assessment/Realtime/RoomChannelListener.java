package SeSAC.Dance_Assessment.Realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 방 채널에서 받은 메시지를 이 서버의 메모리 브로커({@code /topic/rooms.{id}})로 넘긴다 →
 * 브로커가 이 서버의 구독 세션들에 전달한다.
 *
 * <p><b>전달 경로는 이것 하나다.</b> 발행한 서버도 자기 메시지를 Redis에서 다시 받아 전달한다 —
 * 직접 보내기와 Redis 경로를 섞으면 같은 서버 사용자와 다른 서버 사용자가 받는 순서·중복이 달라진다.
 *
 * <p>컨테이너가 단일 스레드 실행기로 부르므로(RedisConfig) Redis가 보낸 순서대로 넘긴다.
 */
@Slf4j
@Component
public class RoomChannelListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomChannelListener(ObjectMapper objectMapper,
                               @Lazy @Qualifier("brokerMessagingTemplate") SimpMessagingTemplate messagingTemplate) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RoomBroadcast broadcast = objectMapper.readValue(message.getBody(), RoomBroadcast.class);
            messagingTemplate.convertAndSend(ChatDestinations.roomTopic(broadcast.roomId()), broadcast);
        } catch (Exception e) {
            log.error("[Redis 방 메시지 전달 실패] channel={}", new String(message.getChannel()), e);
        }
    }
}
