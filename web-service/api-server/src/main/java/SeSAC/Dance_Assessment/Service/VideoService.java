package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Domain.*;
import SeSAC.Dance_Assessment.Dto.Video.*;
import SeSAC.Dance_Assessment.Common.ApiException;
import SeSAC.Dance_Assessment.Common.ErrorCode;
import SeSAC.Dance_Assessment.Infrastructure.MissionRepository;
import SeSAC.Dance_Assessment.Infrastructure.PracticeLogRepository;
import SeSAC.Dance_Assessment.Infrastructure.UserRepository;
import SeSAC.Dance_Assessment.Infrastructure.VideoRepository;
import SeSAC.Dance_Assessment.Storage.StorageKeys;
import SeSAC.Dance_Assessment.Storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 영상 등록·조회.
 *
 * <p><b>업로드는 두 단계다.</b> 파일 바이트가 이 서버를 지나가지 않기 때문이다.
 *
 * <ol>
 *   <li>{@link #createUploadUrl} — 영상 행을 만들고 서명된 PUT URL을 발급</li>
 *   <li>브라우저가 저장소에 직접 업로드</li>
 *   <li>{@link #completeUpload} — 완료 통보. 이때 연습 기록이 생긴다</li>
 * </ol>
 *
 * <p>예전에는 {@code MultipartFile}을 받아 서버 로컬 디스크에 저장했다. 그 방식은
 * (1) 100MB 파일이 EC2 대역폭과 디스크를 쓰고, (2) 경로가 특정 머신에 묶여
 * 분석 서버가 맥미니로 분리되는 순간 파일을 찾을 수 없으며, (3) Cloudflare
 * 프록시의 요청 본문 100MB 제한에 걸린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VideoService {

    /** 허용 Content-Type. 임의의 파일이 올라오는 것을 막는다. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "video/mp4", "video/quicktime", "video/x-m4v", "video/webm");

    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final PracticeLogRepository practiceLogRepository;
    private final MissionRepository missionRepository;
    private final AnalysisResultWriter analysisResultWriter;
    private final StorageService storage;

    /**
     * 다시 쓸 수 있는 내 영상 목록.
     *
     * <p><b>기준 영상을 매번 다시 올리지 않기 위한 것이다.</b> 같은 안무를 반복해
     * 연습하는 것이 이 서비스의 용도인데, 그때마다 같은 파일을 다시 업로드하고
     * 분석 서버가 같은 영상에서 포즈를 다시 뽑았다(기준 영상 추출 실측 46초).
     */
    public List<VideoSummaryResponse> listMyVideos(Long userId, VideoType type) {
        return videoRepository
                .findByUploaderIdAndTypeAndPurposeAndUploadCompletedTrueOrderByCreatedAtDesc(
                        userId, type, VideoPurpose.GENERAL)
                .stream()
                .map(VideoSummaryResponse::from)
                .toList();
    }

    /**
     * 영상 행을 만들고 저장소에 올릴 서명된 URL을 발급한다.
     *
     * <p>이 시점의 영상은 아직 <b>업로드 미완료</b> 상태다. 분석 대상이 되지 않는다.
     *
     * <p><b>{@code @Transactional}을 이 메서드에서 떼지 말 것.</b> 클래스 기본값이
     * {@code readOnly = true}라, 빠지면 INSERT가 읽기 전용 트랜잭션에서 돈다.
     * PostgreSQL은 이를 거부해 500이 난다 — 9/9에 이 애너테이션과 메서드 사이에 다른
     * 메서드가 끼어들어 운영 업로드가 깨졌고, 당시 테스트 DB(H2)는 이를 잡지 못했다.
     */
    @Transactional
    public VideoUploadUrlResponse createUploadUrl(Long userId, VideoUploadUrlRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        String contentType = request.contentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "지원하지 않는 형식입니다: " + contentType + " (mp4/mov/webm만 가능)");
        }

        VideoType type = request.type() != null ? request.type() : VideoType.PRACTICE;
        String key = StorageKeys.video(userId, request.filename());

        Video video = videoRepository.save(Video.builder()
                .uploader(user)
                .objectKey(key)
                .contentType(contentType)
                .type(type)
                .purpose(request.purpose())
                .originalName(request.filename())
                .build());

        return new VideoUploadUrlResponse(
                video.getId(), storage.createUploadUrl(key, contentType));
    }

    /**
     * 업로드 완료를 기록하고, <b>자유 연습의 연습 영상인 경우에만</b> 연습 기록을 만든다.
     *
     * <p>기준 영상은 "따라 할 대상"이지 누군가의 연습이 아니다. 예전에는 종류를
     * 가리지 않고 기록을 만들어서, 기준 영상을 올릴 때마다 비교 대상이 없어
     * 분석이 불가능한 기록이 하나씩 쌓였다.
     *
     * <p><b>Mission 영상(용도 MISSION)은 종류와 상관없이 기록도 분석 결과도 만들지 않는다.</b>
     * 미션에 거는 것은 업로드가 끝난 뒤 미션 생성·제출 API가 한다.
     */
    @Transactional
    public VideoUploadResponse completeUpload(Long userId, Long videoId, VideoCompleteRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다."));

        if (!video.getUploader().getId().equals(userId)) {
            throw new IllegalStateException("본인이 올린 영상만 등록할 수 있습니다.");
        }
        video.markUploadCompleted();

        if (video.isMission() || video.getType() != VideoType.PRACTICE) {
            return new VideoUploadResponse(video.getId(), null, false);
        }

        Video referenceVideo = null;
        if (request != null && request.referenceVideoId() != null) {
            referenceVideo = videoRepository.findById(request.referenceVideoId())
                    .orElseThrow(() -> new IllegalArgumentException("기준 영상을 찾을 수 없습니다."));
            // 남의 영상을 기준으로 걸면 그 영상이 분석 서버로 넘어가 처리되고, 구간 비교
            // 이미지에 남의 영상 장면이 들어갈 수 있다(비교 영상은 현재 만들지 않는다).
            // 자유 연습은 나 혼자 하는 연습이라 기준 영상도 내 것만 쓸 수 있다.
            if (!referenceVideo.getUploader().getId().equals(userId)) {
                throw new IllegalStateException("본인이 올린 기준 영상만 사용할 수 있습니다.");
            }
            // Mission 영상을 자유 연습의 기준으로 걸면 분석 서버로 넘어간다.
            // Mission 영상은 분석하지 않는다.
            if (referenceVideo.isMission()) {
                throw new IllegalStateException("미션 영상은 자유 연습의 기준 영상으로 쓸 수 없습니다.");
            }
        }

        PracticeLog practiceLog = practiceLogRepository.save(PracticeLog.builder()
                .user(user)
                .practiceVideo(video)
                .referenceVideo(referenceVideo)
                .build());

        // 업로드 직후 분석을 시작하지 않는다. 사용자가 안무 시작 시각을 지정하기
        // 전이라, 구간 없이 돌리면 버릴 결과를 2~3분 걸려 만드는 꼴이 된다.
        // 흐름은 [업로드 → 구간 지정 → 분석 요청]이다.
        if (referenceVideo != null) {
            analysisResultWriter.findOrCreateResult(practiceLog);
        }

        return new VideoUploadResponse(
                video.getId(), practiceLog.getId(), referenceVideo != null);
    }

    /**
     * 재생용 서명 URL을 만든다. <b>권한 검사는 여기서 한다.</b>
     *
     * <p>저장소 버킷은 비공개다. 공개해두면 URL을 아는 누구나 접근할 수 있어
     * 아래 권한 규칙이 통째로 무력해진다.
     */
    public String createPlaybackUrl(Long videoId, Long viewerId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다."));
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        if (!canView(video, viewer)) {
            throw new IllegalStateException("해당 영상을 볼 권한이 없습니다.");
        }
        if (video.getObjectKey() == null) {
            throw new IllegalArgumentException("업로드되지 않은 영상입니다.");
        }
        return storage.createDownloadUrl(video.getObjectKey());
    }

    /**
     * 누가 이 영상을 볼 수 있는가.
     *
     * <ul>
     *   <li><b>본인이 올린 영상</b>은 언제나. 팀을 나가거나 추방된 뒤에도 자기가 올린
     *       Mission 영상은 볼 수 있다(미션 정보에는 접근하지 못한다)</li>
     *   <li><b>자유 연습 영상(GENERAL)</b>은 본인만. 같은 팀원이어도 볼 수 없다</li>
     *   <li><b>Mission 영상</b>은 그 영상이 삭제되지 않은 미션의 레퍼런스이거나 제출 영상이고,
     *       보는 사람이 그 미션 팀의 활동 중 멤버일 때. 용도만으로는 어느 팀의 영상인지
     *       알 수 없어 미션·제출 테이블을 거쳐 확인한다</li>
     * </ul>
     */
    private boolean canView(Video video, User viewer) {
        if (video.getUploader().getId().equals(viewer.getId())) {
            return true;
        }
        if (!video.isMission()) {
            return false;
        }
        return missionRepository.isReferenceVisible(video.getId(), viewer.getId(), TeamMemberStatus.ACTIVE)
                || missionRepository.isSubmissionVisible(video.getId(), viewer.getId(), TeamMemberStatus.ACTIVE);
    }

    @Transactional
    public void deleteVideo(Long videoId, Long requesterId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다."));

        if (!video.getUploader().getId().equals(requesterId)) {
            throw new IllegalStateException("삭제 권한이 없습니다.");
        }

        // Mission 영상은 미션·제출이 FK로 참조한다. 지우면 FK 위반이므로 처음부터 거부한다.
        if (video.isMission()) {
            throw new ApiException(ErrorCode.MISSION_VIDEO_NOT_DELETABLE);
        }

        String key = video.getObjectKey();

        // DB를 먼저 지운다. 반대로 하면 파일만 사라지고 DB에 남아
        // "재생하면 깨지는 기록"이 생긴다.
        //
        // **flush()가 반드시 있어야 한다.** JPA는 DELETE를 커밋할 때 실행한다(쓰기 지연).
        // flush 없이 저장소 파일을 지우면 실제 순서는 "파일 삭제 → DELETE"가 되고, DELETE가
        // FK 위반(연습 기록이 이 영상을 참조)으로 롤백되면 바로 위에서 막으려던 상태가 된다.
        // 여기서 DELETE를 먼저 실행해, 실패하면 파일은 건드리지 않고 409로 응답한다.
        try {
            videoRepository.delete(video);
            videoRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.VIDEO_IN_USE);
        }

        if (key != null) {
            storage.delete(key);
        }
    }
}
