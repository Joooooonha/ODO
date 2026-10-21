package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Dto.Chat.ChatSendRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 세션 하나에서 온 전송 메시지를 처리하고, 보낸 세션에 돌려줄 응답을 정한다.
 *
 * <p><b>순서 불변식: 메시지 k는 k−1이 저장됐거나 영원히 거절된 뒤에만 처리된다.</b>
 * 그래서 서버 사정으로 실패하면 거절하고 다음으로 넘어가지 않고 세션을 멈춘다. 멈춘 동안 온
 * 메시지(대기열에 있던 것, 실패 순간 네트워크 위에 있던 것)는 응답 없이 버린다 — 브라우저는
 * 확정받은 메시지만 보낼 목록에서 지우므로, 버린 메시지는 재개할 때 순서대로 다시 온다.
 *
 * <p><b>멈춤 표시를 응답보다 먼저 한다.</b> 이 메서드는 응답을 "돌려줄" 뿐 보내지 않으므로,
 * 응답 전송이 예외를 던져도(이미 끊긴 세션 등) 멈춤은 이미 걸려 있다.
 *
 * <p><b>분류되지 않은 예외(4.6).</b> 첫 발생은 재시도 소진과 같이 멈춤이다 — 일시적이었다면 재개 때
 * 순서대로 복구된다. 재개 뒤 같은 메시지가 또 분류되지 않은 예외로 실패하면 결정적인 버그로 보고
 * 그 메시지만 {@code INTERNAL_ERROR}로 거절하고 멈추지 않는다("영원히 거절"이라 순서 불변식에 맞는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSendHandler {

    private final ChatMessageService chatMessageService;

    public ChatReply handle(ChatSendSession session, long userId, long roomId, ChatSendRequest request) {
        if (session.isClosed()) {
            return ChatReply.NONE;
        }
        if (session.isHalted()) {
            if (!request.isResume()) {
                return ChatReply.NONE;
            }
            session.resume();
            log.info("[채팅 세션 재개] userId={} roomId={} clientMessageId={}",
                    userId, roomId, request.clientMessageId());
        }

        SendOutcome outcome = chatMessageService.sendText(
                userId, roomId, request.clientMessageId(), request.content());

        // Java 17이라 switch 패턴 대신 instanceof로 나눈다. SendOutcome이 sealed라 경우는 이 넷뿐이다.
        if (outcome instanceof SendOutcome.Saved saved) {
            session.forgetFailure(saved.message().clientMessageId());
            return new ChatReply.Ack(saved.message(), saved.duplicate());
        }
        if (outcome instanceof SendOutcome.Rejected rejected) {
            return new ChatReply.Error(roomId, request.clientMessageId(), rejected.error());
        }
        if (outcome instanceof SendOutcome.RetryExhausted exhausted) {
            session.halt();
            log.warn("[채팅 세션 멈춤] 재시도 소진 userId={} roomId={} clientMessageId={} attempts={}",
                    userId, roomId, request.clientMessageId(), exhausted.attempts());
            return new ChatReply.Error(roomId, request.clientMessageId(), ChatSendError.SERVER_RETRY_EXHAUSTED);
        }
        return onUnclassified(session, userId, roomId, request, (SendOutcome.Unclassified) outcome);
    }

    private ChatReply onUnclassified(ChatSendSession session, long userId, long roomId,
                                     ChatSendRequest request, SendOutcome.Unclassified outcome) {
        // 입력 검증을 통과한 뒤에만 여기로 오므로 UUID는 형식이 맞다.
        UUID clientMessageId = UUID.fromString(request.clientMessageId());
        Throwable failure = outcome.failure();

        if (session.recordUnclassifiedFailure(clientMessageId)) {
            session.halt();
            log.error("[채팅 분류되지 않은 예외] 1번째 실패 → 세션 멈춤 userId={} roomId={} clientMessageId={} 예외={}: {}",
                    userId, roomId, clientMessageId, failure.getClass().getName(), failure.getMessage(), failure);
            return new ChatReply.Error(roomId, request.clientMessageId(), ChatSendError.SERVER_RETRY_EXHAUSTED);
        }
        log.error("[채팅 분류되지 않은 예외] 2번째 이상 실패 → 이 메시지만 거절(INTERNAL_ERROR) userId={} roomId={} clientMessageId={} 예외={}: {}",
                userId, roomId, clientMessageId, failure.getClass().getName(), failure.getMessage(), failure);
        return new ChatReply.Error(roomId, request.clientMessageId(), ChatSendError.INTERNAL_ERROR);
    }
}
