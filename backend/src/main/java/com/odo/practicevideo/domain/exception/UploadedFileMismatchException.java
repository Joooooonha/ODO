package com.odo.practicevideo.domain.exception;

public class UploadedFileMismatchException extends RuntimeException {

	public UploadedFileMismatchException() {
		super("올라온 파일의 크기나 형식이 업로드를 시작할 때와 다르다");
	}

}
