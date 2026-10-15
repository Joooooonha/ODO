package SeSAC.Dance_Assessment.Dto.Mission;

import SeSAC.Dance_Assessment.Domain.SubmissionComment;
import SeSAC.Dance_Assessment.Dto.ApiTime;

import java.time.Instant;

/** 나가거나 추방된 사람의 코멘트도 그대로 남고, 누가 남겼는지(닉네임) 보인다. */
public record CommentResponse(
        Long commentId,
        Long writerUserId,
        String writerNickname,
        String content,
        Instant createdAt) {

    public static CommentResponse from(SubmissionComment c) {
        return new CommentResponse(c.getId(), c.getWriter().getId(), c.getWriter().getNickname(),
                c.getContent(), ApiTime.utc(c.getCreatedAt()));
    }
}
