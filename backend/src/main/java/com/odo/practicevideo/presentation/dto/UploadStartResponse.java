package com.odo.practicevideo.presentation.dto;

import java.time.Instant;
import java.util.Map;

import com.odo.practicevideo.application.dto.UploadStartResult;

/**
 * 업로드 시작의 응답. 08_PracticeVideo_spec.md의 "업로드 시작".
 * 화면은 upload.url로 upload.method를 써서 파일을 보내고 upload.headers를 그대로 붙인다.
 */
public record UploadStartResponse(PracticeVideoItemResponse video, Upload upload) {

	public record Upload(String url, String method, Map<String, String> headers, Instant expiresAt) {

	}

	public static UploadStartResponse from(UploadStartResult result) {
		return new UploadStartResponse(PracticeVideoItemResponse.from(result.video()),
				new Upload(result.upload().url(), "PUT", result.upload().headers(), result.upload().expiresAt()));
	}

}
