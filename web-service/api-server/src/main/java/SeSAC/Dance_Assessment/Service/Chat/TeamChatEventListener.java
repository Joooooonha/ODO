package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Realtime.ControlEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 팀 트랜잭션이 커밋된 뒤, 같은 스레드에서 ① 제어 이벤트(REVOKED·GRANTED)를 발행하고 ② 팀 채팅방에
 * 안내 메시지를 남긴다.
 *
 * <ul>
 *   <li><b>①을 ②보다 먼저</b> 한다. 둘 다 같은 Redis 연결로 차례로 나가고, 받는 서버도 같은 구독 연결·
 *       단일 스레드로 그 순서대로 처리한다. 그래서 추방 안내가 브로커로 넘어갈 때는 추방된 사람의 허용 방
 *       캐시에서 그 방이 이미 빠져 있어, 추방된 사람은 자기 추방 안내를 받지 않는다(E2·D3)</li>
 *   <li><b>팀 트랜잭션에는 번호 부여·저장이 들어가지 않는다.</b> 안내 저장이 실패해도 팀 처리 결과는
 *       그대로다(WARN 로그만). 커밋 직후 서버가 죽으면 안내가 빠질 수 있다 — 팀 상태는 DB가 원본이고
 *       안내는 신호라 받아들인다</li>
 *   <li>{@code @Async}가 아니다. 목적(팀 트랜잭션과 분리)은 같고, 실패가 로그로 바로 보이며 별도
 *       실행기가 필요 없다</li>
 *   <li><b>이 클래스에는 {@code @Transactional}을 붙이지 않는다.</b> Spring 6.1부터
 *       {@code @TransactionalEventListener}에 붙은 {@code @Transactional}은(클래스에 붙은 것까지)
 *       REQUIRES_NEW·NOT_SUPPORTED가 아니면 기동이 실패한다. 새 트랜잭션은
 *       {@link MessageWriter}(REQUIRES_NEW)가 연다</li>
 *   <li>내용은 문장이 아니라 사건이다({@code {"event":"KICK","targetUserId":7}}). 문장은 화면이
 *       현재 닉네임으로 만든다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamChatEventListener {

    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final ControlEventPublisher controlEvents;

    @TransactionalEventListener
    public void onTeamChatEvent(TeamChatEvent event) {
        if (event.kind() == TeamChatEvent.Kind.JOIN) {
            controlEvents.granted(event.targetUserId(), event.roomId());
        } else {
            controlEvents.revoked(event.targetUserId(), event.roomId());
        }
        try {
            SendOutcome outcome = chatMessageService.sendSystem(event.roomId(), event.eventId(), content(event));
            if (!(outcome instanceof SendOutcome.Saved)) {
                log.warn("[채팅 안내 저장 실패] 팀 처리는 그대로 roomId={} event={} targetUserId={} 결과={}",
                        event.roomId(), event.kind(), event.targetUserId(), outcome);
            }
        } catch (RuntimeException e) {
            log.warn("[채팅 안내 저장 실패] 팀 처리는 그대로 roomId={} event={} targetUserId={}",
                    event.roomId(), event.kind(), event.targetUserId(), e);
        }
    }

    private String content(TeamChatEvent event) {
        try {
            return objectMapper.writeValueAsString(
                    new SystemContent(event.kind().name(), event.targetUserId()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 안내 메시지 본문. 레코드라 키 순서가 늘 같다(event, targetUserId). */
    private record SystemContent(String event, long targetUserId) {
    }
}
