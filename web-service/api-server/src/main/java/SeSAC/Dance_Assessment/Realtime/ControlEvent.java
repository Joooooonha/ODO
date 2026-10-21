package SeSAC.Dance_Assessment.Realtime;

/**
 * 제어 채널({@code chat:control}) 메시지. 모든 서버가 켜질 때부터 구독한다.
 *
 * @param type {@code REVOKED}(나가기·추방) 또는 {@code GRANTED}(참여·재활성화·DM 생성)
 */
public record ControlEvent(String type, long userId, long roomId) {

    public static final String REVOKED = "REVOKED";
    public static final String GRANTED = "GRANTED";
}
