package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자와 팀의 관계. 한 사용자는 여러 팀에 속할 수 있다.
 *
 * <p><b>한 사람-한 팀에 기록은 하나뿐이다</b> ({@code uk_team_member_team_user}).
 * 재참여는 새 기록을 만들지 않고 이 기록을 다시 활성화한다. 같은 참여 요청이
 * 동시에 와도 기록이 하나만 생기게 하는 것도 이 제약이다.
 *
 * <p>제약 이름을 V2 마이그레이션과 똑같이 여기에도 선언한다. 테스트(H2)는
 * 마이그레이션이 아니라 엔티티로 스키마를 만들기 때문에, 빠뜨리면 테스트 DB에만
 * 제약이 없어 동시성 테스트가 아무것도 확인하지 못한다.
 */
@Entity
@Getter
@Table(
        name = "team_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_member_team_user",
                columnNames = {"team_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_member_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TeamRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TeamMemberStatus status;

    @Column(nullable = false)
    private LocalDateTime joinedAt;

    private LocalDateTime leftAt;

    private TeamMember(Team team, User user, TeamRole role) {
        this.team = team;
        this.user = user;
        this.role = role;
        this.status = TeamMemberStatus.ACTIVE;
        this.joinedAt = LocalDateTime.now();
    }

    public static TeamMember leader(Team team, User user) {
        return new TeamMember(team, user, TeamRole.LEADER);
    }

    public static TeamMember member(Team team, User user) {
        return new TeamMember(team, user, TeamRole.MEMBER);
    }

    public boolean isActive() {
        return status == TeamMemberStatus.ACTIVE;
    }

    public boolean isLeader() {
        return role == TeamRole.LEADER;
    }

    /** 스스로 나간 사람의 재참여. 기존 기록을 다시 쓴다. */
    public void reactivate() {
        if (status != TeamMemberStatus.LEFT) {
            throw new IllegalStateException("스스로 나간 멤버만 다시 참여할 수 있습니다: " + status);
        }
        this.status = TeamMemberStatus.ACTIVE;
        this.joinedAt = LocalDateTime.now();
        this.leftAt = null;
    }

    public void leave() {
        deactivate(TeamMemberStatus.LEFT);
    }

    public void kick() {
        deactivate(TeamMemberStatus.KICKED);
    }

    private void deactivate(TeamMemberStatus next) {
        if (status != TeamMemberStatus.ACTIVE) {
            throw new IllegalStateException("활동 중인 멤버가 아닙니다: " + status);
        }
        this.status = next;
        this.leftAt = LocalDateTime.now();
    }
}
