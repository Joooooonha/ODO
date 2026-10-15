package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 팀장이 팀원에게 내는 과제. 대상자는 {@link MissionTarget}, 제출은 {@link MissionSubmission}.
 *
 * <p>삭제는 soft delete({@code deletedAt})다. 삭제된 미션은 목록에서 빠지고 상세·제출물·코멘트를
 * 볼 수 없지만, 행은 남는다 — 나중에 채팅방에 남은 카드가 "삭제된 미션"을 가리킬 수 있어야 한다.
 */
@Entity
@Getter
@Table(name = "mission", indexes = @Index(
        name = "idx_mission_team_created", columnList = "team_id, created_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mission extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mission_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "writer_id", nullable = false)
    private User writer;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reference_video_id", nullable = false)
    private Video referenceVideo;

    private LocalDateTime deletedAt;

    public Mission(Team team, User writer, String title, String description, Video referenceVideo) {
        this.team = team;
        this.writer = writer;
        this.title = title;
        this.description = description;
        this.referenceVideo = referenceVideo;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 대상자 목록은 바꾸지 않는다 — 수정할 수 있는 것은 이 세 가지뿐이다. */
    public void update(String title, String description, Video referenceVideo) {
        this.title = title;
        this.description = description;
        this.referenceVideo = referenceVideo;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}
