package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    // 연습한 사람
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // 내가 연습해서 올린 영상
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "practice_video_id")
    private Video practiceVideo;

    // 보고 따라한 원본 영상 (선생님 영상)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_video_id")
    private Video referenceVideo;

    // practice_log.assignment_id는 매핑하지 않는다. 숙제 기능은 Mission으로 대체되어
    // 없어졌다. 컬럼은 롤백된 직전 jar를 위해 남겨 두고 다음 릴리스에서 지운다.

    // AnalysisResult와 양방향 매핑 (편의성)
    @OneToOne(mappedBy = "practiceLog", fetch = FetchType.LAZY)
    private AnalysisResult analysisResult;

    @Builder
    public PracticeLog(User user, Video practiceVideo, Video referenceVideo) {
        this.user = user;
        this.practiceVideo = practiceVideo;
        this.referenceVideo = referenceVideo;
    }
}
