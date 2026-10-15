package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Domain.*;
import SeSAC.Dance_Assessment.Dto.Video.*;
import SeSAC.Dance_Assessment.Infrastructure.PracticeLogRepository;
import SeSAC.Dance_Assessment.Infrastructure.UserRepository;
import SeSAC.Dance_Assessment.Infrastructure.VideoRepository;
import SeSAC.Dance_Assessment.Storage.StorageKeys;
import SeSAC.Dance_Assessment.Storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                .findByUploaderIdAndTypeAndUploadCompletedTrueOrderByCreatedAtDesc(userId, type)
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
     * PostgreSQL은 이를 거부해 500이 나지만 H2(테스트)는 그대로 통과시킨다 —
     * 9/9에 이 애너테이션과 메서드 사이에 다른 메서드가 끼어들어 운영 업로드가 깨졌고
     * 테스트는 아무것도 잡지 못했다.
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
                .originalName(request.filename())
                .build());

        return new VideoUploadUrlResponse(
                video.getId(), storage.createUploadUrl(key, contentType));
    }

    /**
     * 업로드 완료를 기록하고, <b>연습 영상인 경우에만</b> 연습 기록을 만든다.
     *
     * <p>기준 영상은 "따라 할 대상"이지 누군가의 연습이 아니다. 예전에는 종류를
     * 가리지 않고 기록을 만들어서, 기준 영상을 올릴 때마다 비교 대상이 없어
     * 분석이 불가능한 기록이 하나씩 쌓였다.
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

        if (video.getType() != VideoType.PRACTICE) {
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
     * <b>본인이 올린 영상만</b> 볼 수 있다.
     *
     * <p>예전에는 같은 팀원이 올린 기준 영상도 허용했다. 자유 연습은 나 혼자 하는
     * 연습이라 기준 영상이든 연습 영상이든 팀원에게도 보이지 않는다. 팀원끼리 보는
     * 영상은 Mission의 영상뿐이고, 그 권한은 Mission을 만들 때 그쪽에서 따로 확인한다.
     */
    private boolean canView(Video video, User viewer) {
        return video.getUploader().getId().equals(viewer.getId());
    }

    @Transactional
    public void deleteVideo(Long videoId, Long requesterId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다."));

        if (!video.getUploader().getId().equals(requesterId)) {
            throw new IllegalStateException("삭제 권한이 없습니다.");
        }

        String key = video.getObjectKey();
        videoRepository.delete(video);

        // DB를 먼저 지운다. 반대로 하면 파일만 사라지고 DB에 남아
        // "재생하면 깨지는 기록"이 생긴다.
        if (key != null) {
            storage.delete(key);
        }
    }
}
