/**
 * 팀 API 오류를 화면 문구로 바꾼다.
 *
 * 서버는 `{ error, code }`로 응답하고, client.js가 그 code를 Error에 붙여준다.
 * 상태 코드만으로는 "없는 코드"·"이미 참여 중"·"추방됨"을 구분할 수 없어 code를 본다.
 */
const MESSAGES = {
    JOIN_CODE_NOT_FOUND: '존재하지 않는 참여 코드예요. 코드를 다시 확인해주세요.',
    ALREADY_MEMBER: '이미 참여 중인 팀이에요. 팀 목록에서 확인할 수 있어요.',
    KICKED_FROM_TEAM: '이 팀에서 추방되어 다시 참여할 수 없어요.',
    TEAM_NOT_FOUND: '존재하지 않는 팀이에요.',
    NOT_TEAM_MEMBER: '이 팀의 멤버가 아니라 들어갈 수 없어요.',
    NOT_TEAM_LEADER: '팀장만 할 수 있어요.',
    MEMBER_NOT_FOUND: '이미 팀에 없는 멤버예요.',
    LEADER_CANNOT_LEAVE: '팀장은 팀을 나갈 수 없어요.',
    CANNOT_KICK_LEADER: '팀장은 추방할 수 없어요.',
    MISSION_NOT_FOUND: '존재하지 않는 미션이에요.',
    MISSION_DELETED: '삭제된 미션입니다.',
    SUBMISSION_NOT_FOUND: '존재하지 않는 제출물이에요.',
    NOT_MISSION_TARGET: '이 미션의 대상자가 아니에요.',
    COMMENT_NOT_ALLOWED: '팀장과 제출자만 코멘트를 남길 수 있어요.',
    ALREADY_SUBMITTED: '이미 제출했어요.',
};

export function teamErrorMessage(err, fallback = '요청을 처리하지 못했어요. 잠시 후 다시 시도해주세요.') {
    if (err?.code && MESSAGES[err.code]) return MESSAGES[err.code];
    return err?.serverMessage || fallback;
}

/** Team Space에 더 머무를 수 없는 오류인지 (팀이 없거나, 멤버가 아니게 됨). */
export function isNoAccess(err) {
    return err?.code === 'TEAM_NOT_FOUND' || err?.code === 'NOT_TEAM_MEMBER';
}
