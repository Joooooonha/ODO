package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.Video;
import SeSAC.Dance_Assessment.Domain.VideoPurpose;
import SeSAC.Dance_Assessment.Domain.VideoType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoRepository extends JpaRepository<Video, Long> {

    /**
     * 다시 쓸 수 있는 내 영상 목록.
     *
     * <p>{@code uploadCompleted}가 false인 것은 제외한다 — 서명 URL만 받고 실제
     * 업로드는 하지 않은 행이 남을 수 있는데, 그걸 고르면 분석이 반드시 실패한다.
     * Mission 영상(용도 MISSION)도 제외한다 — 자유 연습의 기준으로 고르면 분석 서버로 넘어간다.
     */
    List<Video> findByUploaderIdAndTypeAndPurposeAndUploadCompletedTrueOrderByCreatedAtDesc(
            Long uploaderId, VideoType type, VideoPurpose purpose);
}
