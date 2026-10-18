package SeSAC.Dance_Assessment.Service.Chat;

/**
 * 메시지가 저장 트랜잭션 안에서 만들어졌다. 커밋 뒤(AFTER_COMMIT)에 받는 쪽이 다른 서버로
 * 방송한다 — 롤백된 메시지는 구조적으로 방송되지 않는다.
 *
 * <p>받는 쪽(Redis 발행)은 실시간 전달 단계(2-2)에서 붙는다.
 */
public record MessageSavedEvent(SavedMessage message) {
}
