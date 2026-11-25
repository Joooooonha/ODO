package com.odo.schedule.presentation;

import java.net.URI;
import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.schedule.application.PracticeRoomService;
import com.odo.schedule.presentation.dto.PracticeRoomRequest;
import com.odo.schedule.presentation.dto.PracticeRoomResponse;
import com.odo.schedule.presentation.dto.PracticeRoomSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연습실 만들기, 목록, 상세, 수정, 삭제. 03_Schedule_spec.md의 "API 명세".
 * 만들기와 목록은 팀 아래에, 연습실 하나는 자기 ID로 둔다(얕은 중첩).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PracticeRoomController {

	private final PracticeRoomService practiceRoomService;

	/** 201과 새 연습실의 주소(Location)를 준다. */
	@PostMapping("/teams/{teamId}/practice-rooms")
	public ResponseEntity<PracticeRoomResponse> createRoom(@LoginAccountId long accountId, @PathVariable long teamId,
			@RequestBody PracticeRoomRequest request) {
		PracticeRoomResponse response = PracticeRoomResponse
			.from(practiceRoomService.createRoom(accountId, teamId, request.toCommand()));
		return ResponseEntity.created(URI.create("/api/practice-rooms/" + response.id())).body(response);
	}

	@GetMapping("/teams/{teamId}/practice-rooms")
	public List<PracticeRoomSummaryResponse> findRooms(@LoginAccountId long accountId, @PathVariable long teamId) {
		return practiceRoomService.findRooms(accountId, teamId).stream().map(PracticeRoomSummaryResponse::from).toList();
	}

	@GetMapping("/practice-rooms/{roomId}")
	public PracticeRoomResponse findRoom(@LoginAccountId long accountId, @PathVariable long roomId) {
		return PracticeRoomResponse.from(practiceRoomService.findRoom(accountId, roomId));
	}

	@PutMapping("/practice-rooms/{roomId}")
	public PracticeRoomResponse updateRoom(@LoginAccountId long accountId, @PathVariable long roomId,
			@RequestBody PracticeRoomRequest request) {
		return PracticeRoomResponse.from(practiceRoomService.updateRoom(accountId, roomId, request.toCommand()));
	}

	/** 성공하면 돌려줄 본문이 없어 204다. */
	@DeleteMapping("/practice-rooms/{roomId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRoom(@LoginAccountId long accountId, @PathVariable long roomId) {
		practiceRoomService.deleteRoom(accountId, roomId);
	}

}
