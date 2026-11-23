package com.odo.performance.presentation;

import java.net.URI;
import java.util.List;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.performance.application.SongService;
import com.odo.performance.presentation.dto.SongNameRequest;
import com.odo.performance.presentation.dto.SongResponse;
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
 * 곡 목록, 곡 만들기, 곡 조회, 이름 수정, 삭제. 06_Performance_spec.md의 "API 명세".
 * 목록과 만들기는 공연 아래에, 곡 하나는 자기 ID로 둔다(얕은 중첩).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SongController {

	private final SongService songService;

	@GetMapping("/performances/{performanceId}/songs")
	public List<SongResponse> findSongs(@LoginAccountId long accountId, @PathVariable long performanceId) {
		return songService.findSongs(accountId, performanceId).stream().map(SongResponse::from).toList();
	}

	/** 201과 새 곡의 주소(Location)를 준다. */
	@PostMapping("/performances/{performanceId}/songs")
	public ResponseEntity<SongResponse> createSong(@LoginAccountId long accountId, @PathVariable long performanceId,
			@RequestBody SongNameRequest request) {
		SongResponse response = SongResponse.from(songService.createSong(accountId, performanceId, request.name()));
		return ResponseEntity.created(URI.create("/api/songs/" + response.id())).body(response);
	}

	@GetMapping("/songs/{songId}")
	public SongResponse findSong(@LoginAccountId long accountId, @PathVariable long songId) {
		return SongResponse.from(songService.findSong(accountId, songId));
	}

	@PutMapping("/songs/{songId}")
	public SongResponse renameSong(@LoginAccountId long accountId, @PathVariable long songId,
			@RequestBody SongNameRequest request) {
		return SongResponse.from(songService.renameSong(accountId, songId, request.name()));
	}

	/** 성공하면 돌려줄 본문이 없어 204다. */
	@DeleteMapping("/songs/{songId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSong(@LoginAccountId long accountId, @PathVariable long songId) {
		songService.deleteSong(accountId, songId);
	}

}
