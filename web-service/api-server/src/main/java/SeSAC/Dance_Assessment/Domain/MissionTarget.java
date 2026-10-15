package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미션의 대상자 한 명. 미션을 만들 때 대상자 수만큼 만들고, 이후 바뀌지 않는다.
 *
 * <p>대상자가 팀을 나가거나 추방돼도 이 행은 남는다. 제출 현황은 그 직전 상태를 그대로 보여준다.
 * 제출({@link MissionSubmission})은 이 행을 가리키므로, 대상자가 아닌 사람은 제출 행을 만들 수 없다.
 */
@Entity
@Getter
@Table(name = "mission_target", uniqueConstraints = @UniqueConstraint(
        name = "uk_mission_target_mission_user", columnNames = {"mission_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionTarget extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "target_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public MissionTarget(Mission mission, User user) {
        this.mission = mission;
        this.user = user;
    }
}
