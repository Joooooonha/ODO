package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "video_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_id")  // 외래키 이름 설정
    private User uploader;


    @Enumerated(EnumType.STRING)   // DB에 숫자가 아닌 문자열("REFERENCE")로 저장
    private VideoType type;

    /**
     * 자유 연습(GENERAL)인지 Mission(MISSION)인지. Mission 영상은 분석하지 않고
     * 연습 기록도 만들지 않으며, 그 미션 팀의 활동 중 멤버가 볼 수 있다.
     * DB 컬럼에는 DEFAULT 'GENERAL'이 있다(롤백된 직전 jar의 INSERT용).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoPurpose purpose;

    /**
     * 저장소 객체 키. 예: {@code videos/3/9f2c....mov}
     *
     * <p>예전에는 서버의 <b>로컬 절대경로</b>였다(`/Users/.../VideoUpload/x.mp4`).
     * 그 방식은 특정 머신에 묶여서, Spring이 EC2로 가고 분석이 맥미니로 가는 순간
     * 어느 쪽도 그 경로를 찾을 수 없다. 이제 위치는 저장소가 알고, 접근은
     * presigned URL로만 한다.
     */
    @Column(name = "object_key")
    private String objectKey;

    /** 브라우저가 올린 실제 Content-Type. presigned URL 서명에 쓰인다. */
    private String contentType;

    /**
     * 사용자가 올린 파일 이름.
     *
     * <p>저장소 키에는 남지 않는다 — 키는 `videos/{사용자}/{임의값}.{확장자}` 형태라
     * 사람이 알아볼 단서가 없다. <b>기준 영상을 다시 쓰려면 목록에서 골라야 하는데,
     * "9월 9일 15:32에 올린 영상" 같은 표시로는 어느 안무인지 알 수 없다.</b>
     * 예전 행에는 값이 없으므로 화면은 업로드 시각으로 대체 표시한다.
     */
    private String originalName;

    /**
     * 업로드가 실제로 끝났는지. presigned 방식은 브라우저가 저장소에 직접 올리므로
     * 서버가 완료 시점을 알 수 없다 — 브라우저가 끝나고 알려줘야 비로소 true가 된다.
     * 이 값이 false인 영상은 분석 대상이 될 수 없다.
     */
    private boolean uploadCompleted;

    private AnalysisStatus analysisStatus;

    // ===== 안무 구간 =====
    // 영상 안에서 안무가 실제로 시작/끝나는 시각(초). 둘 다 nullable.
    //
    // 구간(PracticeLog)이 아니라 영상(Video)에 두는 이유:
    // "이 영상의 어디부터 안무인가"는 영상 자체의 성질이다. 기준 영상 하나를
    // 여러 사용자가 따라하면 시작 시각을 한 번만 표시하면 되고, 프론트의 트림
    // UI도 "비교"가 아니라 "영상"을 대상으로 조작한다.
    //
    // 앞뒤를 실제로 잘라내지 않고 타임스탬프만 저장한다 — 재인코딩은 화질 손실과
    // 시간 소요가 있고, 결과 화면에서 원본 영상을 그대로 재생해야 하기 때문이다.
    private Double choreographyStartSec;

    // 끝 시각은 선택이다. 실측상 시작 시각만으로도 대부분의 이득을 얻고
    // 끝 시각의 추가 이득은 4.7%p였다.
    private Double choreographyEndSec;


    @Builder
    public Video(User uploader, String objectKey, String contentType, VideoType type,
                 VideoPurpose purpose, String originalName) {
        this.uploader = uploader;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.type = type;
        this.purpose = purpose != null ? purpose : VideoPurpose.GENERAL;
        this.originalName = originalName;
        this.uploadCompleted = false;
    }

    public boolean isMission() {
        return purpose == VideoPurpose.MISSION;
    }

    /** 브라우저가 저장소 업로드를 마쳤다고 알려왔을 때. */
    public void markUploadCompleted() {
        this.uploadCompleted = true;
    }

    /**
     * 안무 구간을 설정한다. 각각 null이면 "지정하지 않음"이다.
     *
     * @throws IllegalArgumentException 끝 시각이 시작 시각보다 앞이거나 같을 때,
     *                                  또는 음수일 때
     */
    public void updateChoreographyRange(Double startSec, Double endSec) {
        if (startSec != null && startSec < 0) {
            throw new IllegalArgumentException("시작 시각은 0초 이상이어야 합니다.");
        }
        if (endSec != null && endSec < 0) {
            throw new IllegalArgumentException("끝 시각은 0초 이상이어야 합니다.");
        }
        if (startSec != null && endSec != null && endSec <= startSec) {
            throw new IllegalArgumentException("끝 시각은 시작 시각보다 뒤여야 합니다.");
        }
        this.choreographyStartSec = startSec;
        this.choreographyEndSec = endSec;
    }
}