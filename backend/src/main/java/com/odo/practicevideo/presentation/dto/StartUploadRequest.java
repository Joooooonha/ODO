package com.odo.practicevideo.presentation.dto;

import com.odo.practicevideo.application.dto.StartUploadCommand;

/** 업로드 시작의 본문. fileSize는 바이트, contentType은 video/mp4나 video/quicktime이다. */
public record StartUploadRequest(Long songId, String title, String description, Long fileSize, String contentType) {

	public StartUploadCommand toCommand() {
		return new StartUploadCommand(songId, title, description, fileSize, contentType);
	}

}
