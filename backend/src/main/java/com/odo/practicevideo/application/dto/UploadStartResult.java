package com.odo.practicevideo.application.dto;

import com.odo.common.storage.PresignedUpload;

/** 업로드를 시작한 영상(UPLOADING)과 브라우저가 R2에 올릴 서명된 주소. */
public record UploadStartResult(PracticeVideoResult video, PresignedUpload upload) {

}
