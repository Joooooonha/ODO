package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.SubmissionComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubmissionCommentRepository extends JpaRepository<SubmissionComment, Long> {

    @Query("select c from SubmissionComment c join fetch c.writer"
            + " where c.submission.id = :submissionId order by c.createdAt asc, c.id asc")
    List<SubmissionComment> findBySubmissionWithWriter(@Param("submissionId") Long submissionId);
}
