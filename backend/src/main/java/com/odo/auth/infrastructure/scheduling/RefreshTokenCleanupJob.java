package com.odo.auth.infrastructure.scheduling;

import com.odo.auth.application.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루에 한 번 만료된 갱신 토큰 행을 지운다.
 * 삭제는 여러 서버가 동시에 실행해도 결과가 같으므로 중복 실행을 막는 장치를 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

	private final RefreshTokenService refreshTokenService;

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void deleteExpired() {
		int deleted = refreshTokenService.deleteExpired();
		log.info("만료된 갱신 토큰 {}건을 지웠다", deleted);
	}

}
