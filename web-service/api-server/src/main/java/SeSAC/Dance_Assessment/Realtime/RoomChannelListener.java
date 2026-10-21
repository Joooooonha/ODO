package SeSAC.Dance_Assessment.Realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 방 채널에서 받은 것을 이 서버의 메모리 브로커({@code /topic/rooms.{id}})로 넘긴다 →
 * 브로커가 이 서버의 구독 세션들에 전달한다.
 *
 * <p><b>전달 경로는 이것 하나다.</b> 발행한 서버도 자기 메시지를 Redis에서 다시 받아 전달한다 —
 * 직접 보내기와 Redis 경로를 섞으면 같은 서버 사용자와 다른 서버 사용자가 받는 순서·중복이 달라진다.
 *
 * <p>한 채널에 두 종류가 온다: 메시지({@code type} = TEXT/SYSTEM, {@link RoomBroadcast})와 읽음
 * ({@code type} = READ). 읽음은 여기서 구간을 계산해 붙인다({@link ReadFrame#of}). <b>메시지 전달과 같은
 * 스레드다</b>(컨테이너의 단일 스레드 실행기, RedisConfig) — 처리 시간은 {@code chat.redis.dispatch.duration{type}}.
 *
 * <p>나가는 프레임에는 종류 헤더({@link #FRAME_TYPE_HEADER})를 붙인다. STOMP 헤더(native)가 아니라 브라우저로는
 * 나가지 않고, 브로커가 구독자별 복사본에 그대로 옮긴다(SimpleBrokerMessageHandler의 copyHeadersIfAbsent 확인)
 * → 나가는 쪽 검사가 본문을 다시 읽지 않고 종류별로 센다.
 */
@Slf4j
@Component
public class RoomChannelListener implements MessageListener {

    public static final String FRAME_TYPE_HEADER = "chatFrameType";

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRealtimeMetrics metrics;

    public RoomChannelListener(ObjectMapper objectMapper,
                               @Lazy @Qualifier("brokerMessagingTemplate") SimpMessagingTemplate messagingTemplate,
                               ChatRealtimeMetrics metrics) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long start = System.nanoTime();
        String type = ChatRealtimeMetrics.MESSAGE;
        try {
            JsonNode body = objectMapper.readTree(message.getBody());
            if (ReadFrame.TYPE.equals(body.path("type").asText())) {
                type = ChatRealtimeMetrics.READ;
                ReadFrame frame = ReadFrame.of(objectMapper.treeToValue(body, ReadFrame.Published.class));
                send(frame.roomId(), frame, ChatRealtimeMetrics.READ);
            } else {
                RoomBroadcast broadcast = objectMapper.treeToValue(body, RoomBroadcast.class);
                send(broadcast.roomId(), broadcast, ChatRealtimeMetrics.MESSAGE);
            }
        } catch (Exception e) {
            log.error("[Redis 방 채널 전달 실패] channel={}", new String(message.getChannel()), e);
        } finally {
            metrics.dispatched(type, System.nanoTime() - start);
        }
    }

    private void send(long roomId, Object payload, String frameType) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader(FRAME_TYPE_HEADER, frameType);
        headers.setLeaveMutable(true);
        MessageHeaders messageHeaders = headers.getMessageHeaders();
        messagingTemplate.convertAndSend(ChatDestinations.roomTopic(roomId), payload, messageHeaders);
    }
}
