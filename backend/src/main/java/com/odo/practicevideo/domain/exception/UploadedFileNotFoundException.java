package com.odo.practicevideo.domain.exception;

/** 브라우저가 아직 올리기를 마치지 않았을 수 있어 409다. 영상 행은 그대로 둔다. */
public class UploadedFileNotFoundException extends RuntimeException {

	public UploadedFileNotFoundException() {
		super("저장소에 올라온 파일이 없다");
	}

}
