package com.odo.practicevideo.application.dto;

/** 업로드 시작의 입력. songId, fileSize, contentType이 없으면(null) INVALID_REQUEST다. */
public record StartUploadCommand(Long songId, String title, String description, Long fileSize, String contentType) {

}
