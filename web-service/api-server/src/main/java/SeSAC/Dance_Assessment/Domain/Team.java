package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 팀.
 *
 * <p><b>팀장은 여기에 없다.</b> {@code team.leader_id} 컬럼은 DB에 남아 있지만
 * (롤백된 직전 jar가 읽는다) 이 코드는 매핑하지 않는다. 한 사용자가 여러 팀의
 * 팀장일 수 있어서 팀장은 {@link TeamMember#getRole()}로만 판단한다.
 * 컬럼은 다음 릴리스에서 지운다.
 */
@Entity
@Getter
@Table(
        name = "team",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_join_code",
                columnNames = "join_code"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_id")
    private Long id;

    private String teamName;

    /** 팀 참여 코드. 팀장만 볼 수 있다. 생성 방식은 {@code JoinCodeGenerator}. */
    @Column(name = "join_code", nullable = false, length = 8)
    private String joinCode;

    public Team(String teamName, String joinCode) {
        this.teamName = teamName;
        this.joinCode = joinCode;
    }
}
