package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ReadAccessCache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 이 서버에 붙은 한 사용자의 세션들에서 방 하나의 접근을 끊거나(REVOKED) 연다(GRANTED). 웹소켓 연결은
 * <b>끊지 않는다</b> — 그 방만 끊는다(6.3).
 *
 * <p>끊을 때 순서:
 * <ol>
 *   <li>허용 방 캐시에서 제거 — <b>먼저</b>. 이 순간부터 나가는 메시지 검사가 그 방 메시지를 막는다</li>
 *   <li>방 구독표에서 제거, 비면 Redis 방 채널 해제(같은 서버에 다른 구독자가 있으면 유지)</li>
 *   <li>Spring 메모리 브로커의 구독 제거 — 브로커 채널로 UNSUBSCRIBE를 보낸다(브로커는 어느 채널로 온
 *       UNSUBSCRIBE든 구독표에서 지운다 — spring-messaging 확인)</li>
 *   <li>그 세션에 {@code {type: REVOKED, roomId}}</li>
 * </ol>
 * 2·3(청소)이 실패해도 1이 막으므로 보안은 깨지지 않는다. 클라이언트에 "구독 끊어"만 보내는 방식은 쓰지
 * 않는다.
 *
 * <p>이벤트로 오든 5초 재확인으로 오든 같은 메서드를 탄다. 같은 변경을 두 번 적용해도 결과가 같다.
 */
@Slf4j
@Component
public class ChatAccessControl {

    private final ChatConnectionRegistry registry;
    private final RoomSubscriptions rooms;
    private final ChatReplySender replySender;
    private final MessageChannel brokerChannel;
    private final ReadAccessCache readAccess;

    public ChatAccessControl(ChatConnectionRegistry registry, RoomSubscriptions rooms,
                             @Lazy ChatReplySender replySender,
                             @Lazy @Qualifier("brokerChannel") MessageChannel brokerChannel,
                             ReadAccessCache readAccess) {
        this.readAccess = readAccess;
        this.registry = registry;
        this.rooms = rooms;
        this.replySender = replySender;
        this.brokerChannel = brokerChannel;
    }

    /** @return 실제로 끊은 세션 수(이미 막혀 있던 세션은 세지 않는다) */
    public int revoke(long userId, long roomId) {
        readAccess.revoke(userId, roomId);   // 읽음 모으기의 HTTP 권한 캐시(웹소켓 연결이 없는 사용자 몫)
        int changed = 0;
        for (ChatConnection connection : registry.ofUser(userId)) {
            if (connection.isClosed()) {
                continue;
            }
            List<String> subscriptions = connection.revoke(roomId, rooms);
            if (subscriptions == null) {
                continue;
            }
            changed++;
            subscriptions.forEach(id -> unsubscribeInBroker(connection, id));
            try {
                replySender.revoked(connection, roomId);
            } catch (RuntimeException e) {
                log.warn("[채팅 차단] 알림 전송 실패(차단은 이미 적용) sessionId={} roomId={} 원인={}",
                        connection.sessionId(), roomId, e.toString());
            }
        }
        return changed;
    }

    /** @return 새로 허용한 세션 수 */
    public int grant(long userId, long roomId) {
        readAccess.grant(userId, roomId);
        int changed = 0;
        for (ChatConnection connection : registry.ofUser(userId)) {
            if (connection.isClosed() || !connection.allow(roomId)) {
                continue;
            }
            changed++;
            try {
                replySender.granted(connection, roomId);
            } catch (RuntimeException e) {
                log.warn("[채팅 허용] 알림 전송 실패 sessionId={} roomId={} 원인={}",
                        connection.sessionId(), roomId, e.toString());
            }
        }
        return changed;
    }

    private void unsubscribeInBroker(ChatConnection connection, String subscriptionId) {
        try {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
            headers.setSessionId(connection.sessionId());
            headers.setSubscriptionId(subscriptionId);
            brokerChannel.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
        } catch (RuntimeException e) {
            log.warn("[채팅 차단] 브로커 구독 청소 실패(전달은 이미 막힘) sessionId={} subscriptionId={} 원인={}",
                    connection.sessionId(), subscriptionId, e.toString());
        }
    }
}
