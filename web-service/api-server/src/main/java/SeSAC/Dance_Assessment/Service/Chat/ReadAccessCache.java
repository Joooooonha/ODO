package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Infrastructure.ChatRoomMemberRepository;
import SeSAC.Dance_Assessment.Realtime.ChatConnection;
import SeSAC.Dance_Assessment.Realtime.ChatConnectionRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 쪽 읽음 모으기에서 보고를 받을 때의 권한 확인 — DB에 가지 않는다.
 *
 * <ol>
 *   <li>이 서버에 붙은 그 사용자의 웹소켓 연결이 그 방을 허용하고 있으면(구독 때 쓰는 허용 방 캐시) 허용</li>
 *   <li>아니면 이 서버의 사용자별 캐시. nginx가 HTTP와 웹소켓을 다른 서버로 보낼 수 있어서(번갈아 보냄) 1만으로는
 *       절반이 빗나간다. 처음 한 번만 DB에서 그 사용자의 ACTIVE 방을 읽고, 그 뒤로는 허용 방 캐시와 같은 경로
 *       (제어 채널 이벤트·5초 재확인 → {@link SeSAC.Dance_Assessment.Realtime.ChatAccessControl})로 고친다</li>
 * </ol>
 * 그래서 막 나간 사용자의 보고가 최대 5초쯤 202로 받아질 수 있다. 그래도 <b>쓰지는 못한다</b> — 처리할 때 갱신 조건이
 * "현재 ACTIVE 멤버의 행"이다({@link ReadAggregator}). 이 캐시는 응답 코드(403)를 정할 뿐 정확성을 지키지 않는다.
 */
@Component
public class ReadAccessCache {

    private final ChatConnectionRegistry connections;
    private final ChatRoomMemberRepository members;
    private final Map<Long, Set<Long>> byUser = new ConcurrentHashMap<>();

    public ReadAccessCache(ChatConnectionRegistry connections, ChatRoomMemberRepository members) {
        this.connections = connections;
        this.members = members;
    }

    public boolean isAllowed(long userId, long roomId) {
        for (ChatConnection c : connections.ofUser(userId)) {
            if (!c.isClosed() && c.isAllowed(roomId)) {
                return true;
            }
        }
        return byUser.computeIfAbsent(userId, this::load).contains(roomId);
    }

    private Set<Long> load(long userId) {
        Set<Long> rooms = ConcurrentHashMap.newKeySet();
        rooms.addAll(members.findActiveRoomIds(userId));
        return rooms;
    }

    /** 나가기·추방이 이 서버에 알려졌다(이벤트 또는 재확인). 읽어 둔 사용자만 고친다 */
    public void revoke(long userId, long roomId) {
        Set<Long> rooms = byUser.get(userId);
        if (rooms != null) {
            rooms.remove(roomId);
        }
    }

    public void grant(long userId, long roomId) {
        Set<Long> rooms = byUser.get(userId);
        if (rooms != null) {
            rooms.add(roomId);
        }
    }
}
