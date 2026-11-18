package com.odo.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 갱신 토큰 저장소. 토큰은 SHA-256 해시로만 찾는다. 구현은 infrastructure에 둔다.
 */
public interface RefreshTokenRepository {

	void insert(long accountId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt);

	/**
	 * 아직 쓰지 않았고 만료되지 않은 토큰이면 사용 시각을 기록하고 true.
	 * 같은 토큰으로 동시에 불려도 한 호출만 true를 받는다.
	 */
	boolean markUsedIfUnused(String tokenHash, Instant now);

	Optional<RefreshToken> findByHash(String tokenHash);

	/** 그 family의 행을 모두 지우고 지운 수를 돌려준다. */
	int deleteFamily(UUID familyId);

	/** 만료 시각이 now 이전인 행을 지우고 지운 수를 돌려준다. */
	int deleteExpired(Instant now);

}
