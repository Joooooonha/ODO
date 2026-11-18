package com.odo.auth.presentation.dto;

/**
 * 갱신 응답. expiresIn은 접근 토큰의 남은 유효 시간(초)이다.
 */
public record RefreshResponse(String accessToken, long expiresIn) {

}
