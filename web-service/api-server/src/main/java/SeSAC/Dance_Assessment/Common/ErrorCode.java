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

    NOT_TEAM_MEMBER(HttpStatus.FORBIDDEN, "이 팀의 멤버가 아닙니다."),
    NOT_TEAM_LEADER(HttpStatus.FORBIDDEN, "팀장만 할 수 있습니다."),
    // 상태 충돌이 아니라 "이 팀에 들어올 권한이 없다"는 거부라 403이다.
    KICKED_FROM_TEAM(HttpStatus.FORBIDDEN, "추방되어 이 팀에 참여할 수 없습니다."),

    ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 참여 중인 팀입니다."),
    LEADER_CANNOT_LEAVE(HttpStatus.CONFLICT, "팀장은 팀을 나갈 수 없습니다."),
    CANNOT_KICK_LEADER(HttpStatus.CONFLICT, "팀장은 추방할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
