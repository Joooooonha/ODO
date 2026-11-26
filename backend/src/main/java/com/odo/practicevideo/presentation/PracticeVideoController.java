package com.odo.practicevideo.presentation;

import java.net.URI;
import java.time.LocalDate;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.practicevideo.application.PracticeVideoService;
import com.odo.practicevideo.application.PracticeVideoUploadService;
import com.odo.practicevideo.presentation.dto.PracticeVideoDetailResponse;
import com.odo.practicevideo.presentation.dto.PracticeVideoPageResponse;
import com.odo.practicevideo.presentation.dto.StartUploadRequest;
import com.odo.practicevideo.presentation.dto.UpdatePracticeVideoRequest;
import com.odo.practicevideo.presentation.dto.UploadStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연습 영상의 업로드 시작과 완료, 목록, 상세, 수정, 삭제. 08_PracticeVideo_spec.md의 "API 명세".
 * 목록과 업로드 시작은 공연 아래에, 영상 하나는 자기 ID로 둔다(얕은 중첩).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PracticeVideoController {

	private final PracticeVideoUploadService uploadService;

	private final PracticeVideoService practiceVideoService;

	/** 201과 새 영상의 주소(Location)를 준다. 영상은 업로드 중(UPLOADING)이다. */
	@PostMapping("/performances/{performanceId}/practice-videos")
	public ResponseEntity<UploadStartResponse> startUpload(@LoginAccountId long accountId,
			@PathVariable long performanceId, @RequestBody StartUploadRequest request) {
		UploadStartResponse response = UploadStartResponse
			.from(uploadService.start(accountId, performanceId, request.toCommand()));
		return ResponseEntity.created(URI.create("/api/practice-videos/" + response.video().id())).body(response);
	}

	@PostMapping("/practice-videos/{id}/upload-completion")
	public PracticeVideoDetailResponse completeUpload(@LoginAccountId long accountId, @PathVariable long id) {
		return PracticeVideoDetailResponse.from(uploadService.complete(accountId, id));
	}

	/** from, to는 한국 시간 날짜(yyyy-MM-dd)이고 둘 다 선택이다. cursor는 앞 응답의 nextCursor다. */
	@GetMapping("/performances/{performanceId}/practice-videos")
	public PracticeVideoPageResponse findVideos(@LoginAccountId long accountId, @PathVariable long performanceId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor) {
		return PracticeVideoPageResponse.from(practiceVideoService.findVideos(accountId, performanceId, from, to, cursor));
	}

	@GetMapping("/practice-videos/{id}")
	public PracticeVideoDetailResponse findVideo(@LoginAccountId long accountId, @PathVariable long id) {
		return PracticeVideoDetailResponse.from(practiceVideoService.findVideo(accountId, id));
	}

	@PutMapping("/practice-videos/{id}")
	public PracticeVideoDetailResponse updateVideo(@LoginAccountId long accountId, @PathVariable long id,
			@RequestBody UpdatePracticeVideoRequest request) {
		return PracticeVideoDetailResponse
			.from(practiceVideoService.updateVideo(accountId, id, request.title(), request.description()));
	}

	/** 성공하면 돌려줄 본문이 없어 204다. */
	@DeleteMapping("/practice-videos/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteVideo(@LoginAccountId long accountId, @PathVariable long id) {
		practiceVideoService.deleteVideo(accountId, id);
	}

}
