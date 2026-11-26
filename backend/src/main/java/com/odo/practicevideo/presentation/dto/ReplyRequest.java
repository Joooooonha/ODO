package com.odo.practicevideo.presentation.dto;

/** 스레드에 답하기의 본문. 시점은 받지 않는다(스레드의 시점이다). */
public record ReplyRequest(String content) {
}
