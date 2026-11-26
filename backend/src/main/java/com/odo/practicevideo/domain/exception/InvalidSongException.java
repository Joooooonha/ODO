package com.odo.practicevideo.domain.exception;

/** 본문의 곡이 없거나, 이 공연의 곡이 아니거나, 삭제됐다(400 INVALID_SONG). URL의 곡이 없는 SONG_NOT_FOUND(404)와 다르다. */
public class InvalidSongException extends RuntimeException {

	public InvalidSongException() {
		super("이 공연에서 고를 수 있는 곡이 아니다");
	}

}
