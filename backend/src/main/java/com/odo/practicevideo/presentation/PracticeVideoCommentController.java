package com.odo.practicevideo.presentation;

import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.practicevideo.application.PracticeVideoCommentService;
import com.odo.practicevideo.presentation.dto.CreateThreadRequest;
import com.odo.practicevideo.presentation.dto.PracticeVideoCommentResponse;
import com.odo.practicevideo.presentation.dto.PracticeVideoThreadResponse;
import com.odo.practicevideo.presentation.dto.ReplyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시점 코멘트. 08_PracticeVideo_spec.md의 "코멘트 목록", "스레드 만들기", "스레드에 답하기".
 * 스레드 하나를 읽는 API가 없고 화면이 응답 본문을 목록에 넣으므로, 만들 때 Location은 주지 않는다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PracticeVideoCommentController {

	private final PracticeVideoCommentService commentService;

	@GetMapping("/practice-videos/{id}/threads")
	public List<PracticeVideoThreadResponse> findThreads(@LoginAccountId long accountId, @PathVariable long id) {
		return commentService.findThreads(accountId, id).stream().map(PracticeVideoThreadResponse::from).toList();
	}

	@PostMapping("/practice-videos/{id}/threads")
	@ResponseStatus(HttpStatus.CREATED)
	public PracticeVideoThreadResponse createThread(@LoginAccountId long accountId, @PathVariable long id,
			@RequestBody CreateThreadRequest request) {
		return PracticeVideoThreadResponse
			.from(commentService.createThread(accountId, id, request.positionSeconds(), request.content()));
	}

	@PostMapping("/practice-video-threads/{threadId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public PracticeVideoCommentResponse reply(@LoginAccountId long accountId, @PathVariable long threadId,
			@RequestBody ReplyRequest request) {
		return PracticeVideoCommentResponse.from(commentService.reply(accountId, threadId, request.content()));
	}

}
