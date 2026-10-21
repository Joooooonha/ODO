package SeSAC.Dance_Assessment.Dto.Chat;

/**
 * 메시지 전송 프레임 본문 ({@code SEND /app/rooms/{roomId}/messages}).
 *
 * @param clientMessageId 브라우저가 메시지마다 만든 UUID. 재전송해도 같은 값이다
 * @param resume          멈춘 세션을 재개하는 신호. 멈춤 응답을 받은 뒤 보내는 첫 메시지에만 붙인다
 */
public record ChatSendRequest(String clientMessageId, String content, Boolean resume) {

    public boolean isResume() {
        return Boolean.TRUE.equals(resume);
    }
}
