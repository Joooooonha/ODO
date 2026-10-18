package SeSAC.Dance_Assessment.Service.Chat;

/**
 * 같은 메시지(보낸 사람 + clientMessageId)가 이미 저장돼 있다.
 *
 * <p>실패가 아니라 "이미 성공했다"는 뜻이지만 예외로 던진다 — 트랜잭션을 롤백시켜 이번 시도가
 * 올린 방 번호를 되돌려야 하기 때문이다. {@link ChatMessageService}가 받아서 기존 번호로 성공
 * 응답을 만든다.
 */
class DuplicateMessageException extends RuntimeException {

    private final SavedMessage existing;

    DuplicateMessageException(SavedMessage existing) {
        super(null, null, false, false);
        this.existing = existing;
    }

    SavedMessage existing() {
        return existing;
    }
}
