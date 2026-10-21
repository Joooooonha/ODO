package SeSAC.Dance_Assessment.Service.Chat;

/**
 * 메시지 전송 오류 응답의 {@code code}. 브라우저는 이 값으로 다음 행동을 정한다.
 *
 * <ul>
 *   <li>{@link #NOT_MEMBER}, {@link #INVALID_MESSAGE} — 다시 보내도 소용없다. 그 메시지만
 *       "전송 실패"로 표시하고 다음 메시지로 넘어간다. 세션은 멈추지 않는다</li>
 *   <li>{@link #SERVER_RETRY_EXHAUSTED} — 서버 사정. 세션이 멈췄다. 잠시 뒤 보낼 목록 전체를
 *       {@code resume: true}와 함께 처음부터 다시 보낸다</li>
 *   <li>{@link #INTERNAL_ERROR} — 같은 메시지가 재개 뒤에도 분류되지 않은 예외로 다시 실패했다.
 *       영구 실패로 보고 그 메시지만 거절한다. 세션은 멈추지 않는다(4.6)</li>
 * </ul>
 */
public enum ChatSendError {
    NOT_MEMBER,
    INVALID_MESSAGE,
    SERVER_RETRY_EXHAUSTED,
    INTERNAL_ERROR
}
