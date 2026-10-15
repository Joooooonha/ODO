package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대상자의 제출. 대상자 1인당 1건이며 재제출은 없다.
 *
 * <p>"1인당 1회"는 {@code uk_mission_submission_target}이 DB에서 보장한다. 같은 제출이
 * 동시에 와도 한 건만 들어가고, 나머지는 제약 위반으로 실패한다.
 * 제출 시각은 {@code created_at}이다(한 번 INSERT하고 끝나므로 따로 두지 않는다).
 */
@Entity
@Getter
@Table(name = "mission_submission", uniqueConstraints = @UniqueConstraint(
        name = "uk_mission_submission_target", columnNames = "target_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionSubmission extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "submission_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private MissionTarget target;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(columnDefinition = "text")
    private String content;

    public MissionSubmission(MissionTarget target, Video video, String content) {
        this.target = target;
        this.video = video;
        this.content = content;
    }
}
