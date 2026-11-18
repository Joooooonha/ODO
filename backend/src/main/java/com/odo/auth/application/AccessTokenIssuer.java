package com.odo.auth.application;

import java.time.Duration;

/**
 * 접근 토큰 발급. 본문에는 계정 ID, 발급 시각, 만료 시각만 넣는다.
 * 팀 역할은 요청마다 DB에서 확인하므로 넣지 않는다. 구현(JWT)은 infrastructure에 둔다.
 */
public interface AccessTokenIssuer {

	Duration TTL = Duration.ofMinutes(30);

	String issue(long accountId);

}
