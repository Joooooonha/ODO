package SeSAC.Dance_Assessment.Realtime;

import org.springframework.data.redis.listener.ChannelTopic;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 목적지와 Redis 채널 이름. 이름 규칙은 여기 한 곳에만 둔다.
 *
 * <ul>
 *   <li>방 구독: {@code /topic/rooms.{roomId}}</li>
 *   <li>전송: {@code /app/rooms/{roomId}/messages}, 구독 장벽: {@code /app/sync}</li>
 *   <li>개인: {@code /user/queue/acks}(확정·오류, 보낸 세션에만), {@code /user/queue/events}(차단·허용)</li>
 *   <li>Redis: {@code chat:room:{roomId}}(방별), {@code chat:control}(제어, 모든 서버)</li>
 * </ul>
 */
public final class ChatDestinations {

    public static final String ROOM_TOPIC_PREFIX = "/topic/rooms.";
    public static final String ACKS = "/queue/acks";
    public static final String EVENTS = "/queue/events";
    public static final String USER_ACKS = "/user" + ACKS;
    public static final String USER_EVENTS = "/user" + EVENTS;
    public static final String CONTROL_CHANNEL = "chat:control";
    public static final String SYNC = "/app/sync";

    private static final String ROOM_CHANNEL_PREFIX = "chat:room:";
    private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms\\.(\\d{1,18})$");
    private static final Pattern SEND = Pattern.compile("^/app/rooms/(\\d{1,18})/messages$");

    private ChatDestinations() {
    }

    public static String roomTopic(long roomId) {
        return ROOM_TOPIC_PREFIX + roomId;
    }

    public static ChannelTopic roomChannel(long roomId) {
        return new ChannelTopic(ROOM_CHANNEL_PREFIX + roomId);
    }

    /** {@code /topic/rooms.{id}} 형식이면 방 id. 다른 형식이면 empty. */
    public static OptionalLong roomOfTopic(String destination) {
        return match(ROOM_TOPIC, destination);
    }

    public static OptionalLong roomOfSend(String destination) {
        return match(SEND, destination);
    }

    private static OptionalLong match(Pattern pattern, String destination) {
        if (destination == null) {
            return OptionalLong.empty();
        }
        Matcher m = pattern.matcher(destination);
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
