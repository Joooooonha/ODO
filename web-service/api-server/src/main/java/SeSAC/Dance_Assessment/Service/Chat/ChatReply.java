package SeSAC.Dance_Assessment.Service.Chat;

/** 보낸 세션에만 돌려줄 응답. 무엇을 보낼지만 정하고, 보내는 일은 호출한 쪽(STOMP)이 한다. */
public sealed interface ChatReply {

    /** 확정. {@code duplicate}면 이미 저장돼 있던 메시지의 번호다. */
    record Ack(SavedMessage message, boolean duplicate) implements ChatReply {
    }

    record Error(long roomId, String clientMessageId, ChatSendError code) implements ChatReply {
    }

    /** 응답 없이 버린다(멈춤 중이거나 끊긴 세션). 브라우저는 확정을 못 받았으니 나중에 다시 보낸다. */
    record None() implements ChatReply {
    }

    ChatReply NONE = new None();
}
