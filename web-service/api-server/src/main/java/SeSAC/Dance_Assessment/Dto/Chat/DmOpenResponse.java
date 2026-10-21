package SeSAC.Dance_Assessment.Dto.Chat;

/** @param created 이번 요청으로 새로 만들었으면 true, 이미 있던 방이면 false */
public record DmOpenResponse(long roomId, boolean created) {
}
