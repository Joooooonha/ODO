package com.odo.practicevideo.presentation.dto;

/** 스레드 만들기의 본문. positionSeconds는 재생 중인 위치(초, 소수점 아래 버림)다. 없으면 400 INVALID_REQUEST. */
public record CreateThreadRequest(Integer positionSeconds, String content) {
}
