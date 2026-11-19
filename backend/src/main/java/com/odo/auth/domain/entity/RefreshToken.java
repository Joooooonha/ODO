package com.odo.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 저장된 갱신 토큰 한 행. 원문은 저장하지 않으므로 여기에도 없다.
 * usedAt이 null이면 아직 쓰지 않은 토큰이다.
 */
public record RefreshToken(long accountId, UUID familyId, Instant expiresAt, Instant usedAt) {

}
