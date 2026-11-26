package com.odo.common.storage;

import java.time.Instant;
import java.util.Map;

/**
 * 서명된 업로드 주소.
 *
 * @param url PUT으로 파일을 보낼 주소
 * @param headers 브라우저가 그대로 붙여야 하는 헤더(Content-Type). Content-Length는 브라우저가 파일 크기로 채운다
 * @param expiresAt 이 시각까지 전송을 시작해야 한다
 */
public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {

}
