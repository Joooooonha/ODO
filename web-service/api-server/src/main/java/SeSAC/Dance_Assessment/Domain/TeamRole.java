package SeSAC.Dance_Assessment.Domain;

/**
 * 팀 안에서의 역할.
 *
 * <p>팀장은 팀에 정확히 1명이다. 운영(PostgreSQL)에서는 부분 유니크 인덱스
 * {@code uk_team_member_one_leader}가 보장한다 — JPA로는 선언할 수 없어서
 * 테스트(H2)에는 이 제약이 없다.
 */
public enum TeamRole {
    LEADER,
    MEMBER
}
