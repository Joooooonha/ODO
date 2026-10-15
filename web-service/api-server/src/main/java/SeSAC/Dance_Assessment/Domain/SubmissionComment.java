package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 제출물에 다는 코멘트. 작성은 팀장과 그 제출자만, 읽기는 팀의 활동 중 멤버 누구나.
 * 수정·삭제는 없다.
 */
@Entity
@Getter
@Table(name = "submission_comment", indexes = @Index(
        name = "idx_submission_comment_submission_created", columnList = "submission_id, created_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionComment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private MissionSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "writer_id", nullable = false)
    private User writer;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    public SubmissionComment(MissionSubmission submission, User writer, String content) {
        this.submission = submission;
        this.writer = writer;
        this.content = content;
    }
}
