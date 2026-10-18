package SeSAC.Dance_Assessment.Service.Chat;

/**
 * 메시지 저장 시도의 결과. 예외 대신 값으로 돌려줘서, 세션 처리({@link ChatSendHandler})가
 * 모든 경우를 빠짐없이 다루게 한다.
 */
public sealed interface SendOutcome {

    /** 저장됐다. {@code duplicate}면 새로 저장한 게 아니라 같은 메시지가 이미 있었다(재전송). */
    record Saved(SavedMessage message, boolean duplicate) implements SendOutcome {
    }

    /** 다시 보내도 소용없는 실패(멤버 아님, 입력 오류). */
    record Rejected(ChatSendError error) implements SendOutcome {
    }

    /** 다시 해 볼 만한 실패가 재시도 끝까지 이어졌다. */
    record RetryExhausted(Throwable lastFailure, int attempts) implements SendOutcome {
    }

    /** 분류되지 않은 예외(버그일 가능성이 크다). 재시도하지 않았다. */
    record Unclassified(Throwable failure) implements SendOutcome {
    }
}
