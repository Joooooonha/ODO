package SeSAC.Dance_Assessment.Realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 연결 중에 토큰이 만료된 세션을 끊는다(E5). 웹소켓은 핸드셰이크 때 한 번만 인증하므로, 이게 없으면
 * 만료 직전에 연결해 둔 (도난) 토큰으로 무기한 받을 수 있다. 끊으면 브라우저가 재연결하고, 쿠키가
 * 만료됐으면 핸드셰이크가 401로 실패한다 → 브라우저가 {@code /api/users/me}로 확인하고 로그인 화면으로.
 */
@Component
@RequiredArgsConstructor
public class TokenExpiryChecker {

    private final ChatConnectionRegistry registry;
    private final ConnectionCloser closer;

    @Scheduled(fixedDelayString = "${chat.ws.token-check-interval:60s}")
    public void check() {
        closeExpired(Instant.now());
    }

    /** @return 끊은 세션 수 */
    public int closeExpired(Instant now) {
        int closed = 0;
        for (ChatConnection connection : registry.all()) {
            if (!connection.isClosed() && !connection.tokenExpiresAt().isAfter(now)) {
                closer.closeWithError(connection, ConnectionCloser.TOKEN_EXPIRED, ChatRealtimeMetrics.TOKEN_EXPIRED);
                closed++;
            }
        }
        return closed;
    }
}
