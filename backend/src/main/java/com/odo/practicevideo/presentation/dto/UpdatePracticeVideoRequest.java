package com.odo.practicevideo.presentation.dto;

/** 영상 수정의 본문. 제목과 설명을 모두 보낸다. 설명이 비어 있으면 설명을 지운다. */
public record UpdatePracticeVideoRequest(String title, String description) {

}
