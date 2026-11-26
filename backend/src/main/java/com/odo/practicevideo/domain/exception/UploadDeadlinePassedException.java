package com.odo.practicevideo.domain.exception;

public class UploadDeadlinePassedException extends RuntimeException {

	public UploadDeadlinePassedException() {
		super("업로드 완료 기한이 지났다. 새로 올린다");
	}

}
