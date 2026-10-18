package SeSAC.Dance_Assessment.Common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 화면이 구분해서 안내해야 하는 오류.
 *
 * <p>상태 코드만으로는 부족하다. 팀 참여 화면은 "없는 코드"(404)와 "이미 참여 중"(409),
 * "추방되어 참여 불가"(403)를 각각 다르게 안내해야 하는데, 같은 403 안에서도
 * "멤버가 아님"과 "추방됨"이 갈린다. 그래서 응답에 {@code code}를 함께 싣는다.
 *
 * <ul>
 *   <li>400 — 요청 자체가 성립하지 않음 (자기 자신에게 DM 등)</li>
 *   <li>404 — 대상이 없음</li>
 *   <li>403 — 대상은 있지만 이 사용자에게 권한이 없음</li>
 *   <li>409 — 현재 상태와 충돌 (이미 참여 중, 팀장은 나갈 수 없음 등)</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 팀입니다."),
    JOIN_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 참여 코드입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "팀에서 활동 중인 멤버가 아닙니다."),
    MISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 미션입니다."),
    // 삭제된 미션도 "없음"(404)이지만, 화면이 "삭제된 미션입니다"로 따로 안내하도록 코드를 나눈다.
    MISSION_DELETED(HttpStatus.NOT_FOUND, "삭제된 미션입니다."),
    SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 제출물입니다."),

    NOT_TEAM_MEMBER(HttpStatus.FORBIDDEN, "이 팀의 멤버가 아닙니다."),
    NOT_TEAM_LEADER(HttpStatus.FORBIDDEN, "팀장만 할 수 있습니다."),
    // 상태 충돌이 아니라 "이 팀에 들어올 권한이 없다"는 거부라 403이다.
    KICKED_FROM_TEAM(HttpStatus.FORBIDDEN, "추방되어 이 팀에 참여할 수 없습니다."),
    NOT_MISSION_TARGET(HttpStatus.FORBIDDEN, "이 미션의 대상자가 아닙니다."),
    COMMENT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "팀장과 제출자만 코멘트를 남길 수 있습니다."),
    // 방이 없을 때도 이 코드다(404가 아니라). 방 번호만으로 방이 있는지 알아낼 수 없게 한다.
    NOT_CHAT_MEMBER(HttpStatus.FORBIDDEN, "이 채팅방의 멤버가 아닙니다."),
    // 지금 같은 팀도 아니고 예전 DM도 없다. 다시 같은 팀이 되면 열 수 있다.
    DM_NOT_ALLOWED(HttpStatus.FORBIDDEN, "같은 팀 멤버에게만 DM을 보낼 수 있습니다."),

    CANNOT_DM_SELF(HttpStatus.BAD_REQUEST, "자기 자신에게는 DM을 보낼 수 없습니다."),

    ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 참여 중인 팀입니다."),
    LEADER_CANNOT_LEAVE(HttpStatus.CONFLICT, "팀장은 팀을 나갈 수 없습니다."),
    CANNOT_KICK_LEADER(HttpStatus.CONFLICT, "팀장은 추방할 수 없습니다."),
    ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 제출했습니다."),
    // Mission 영상은 미션·제출이 FK로 참조한다. 지우면 FK 위반이므로 처음부터 막는다.
    MISSION_VIDEO_NOT_DELETABLE(HttpStatus.CONFLICT, "미션에 쓰인 영상은 삭제할 수 없습니다."),
    // 연습 기록 등이 참조하는 영상. DELETE를 먼저 실행해 FK 위반이면 이 코드로 응답한다.
    VIDEO_IN_USE(HttpStatus.CONFLICT, "다른 기록에서 쓰고 있는 영상이라 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
