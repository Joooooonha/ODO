package SeSAC.Dance_Assessment.Dto.Video;

import SeSAC.Dance_Assessment.Domain.VideoPurpose;
import SeSAC.Dance_Assessment.Domain.VideoType;

/**
 * 업로드 URL 발급 요청.
 *
 * <p>파일 자체는 보내지 않는다 — 브라우저가 저장소에 직접 올린다.
 * 여기서는 "무엇을 올릴 것인지"만 알려주고 서명된 URL을 받아간다.
 *
 * @param filename    원본 파일명. 확장자를 얻는 용도로만 쓰고 키에는 넣지 않는다
 * @param contentType 실제로 PUT할 Content-Type. <b>서명에 포함되므로 업로드 시
 *                    같은 값을 보내야 한다.</b> 다르면 저장소가 거부한다
 * @param type        기준 영상인지 연습 영상인지
 * @param purpose     자유 연습(GENERAL, 기본값)인지 Mission(MISSION)인지. 보내지 않으면 GENERAL —
 *                    기존 화면과 배포 전에 열려 있던 옛 탭은 그대로 동작한다
 */
public record VideoUploadUrlRequest(
        String filename,
        String contentType,
        VideoType type,
        VideoPurpose purpose) {
}
