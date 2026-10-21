package SeSAC.Dance_Assessment.Service.Chat;

/** DM 방이 새로 생겼다. 커밋 뒤에 두 사람에게 GRANTED를 보낸다. */
public record DmOpenedEvent(long roomId, long userLow, long userHigh) {
}
