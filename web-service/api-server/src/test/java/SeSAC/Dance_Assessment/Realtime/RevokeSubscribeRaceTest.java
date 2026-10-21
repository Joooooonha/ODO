package SeSAC.Dance_Assessment.Realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 차단(REVOKED)과 같은 방 구독이 동시에 일어나도, 끝난 뒤 "차단된 방인데 방 구독표에 세션이 남은" 상태가
 * 없다. 둘 다 연결 객체의 잠금 아래에서 일어나기 때문이다(구독은 그사이 차단됐으면 하지 않는다).
 */
@SpringBootTest
@ActiveProfiles("dev")
class RevokeSubscribeRaceTest {

    @Autowired
    RoomSubscriptions rooms;

    @Test
    @DisplayName("차단과 구독 동시 200회 → 차단됐으면 구독표에 없다, 차단 전 구독이면 차단이 걷어 낸다")
    void revokeAndSubscribeRace() throws Exception {
        long room = 9_100_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                WebSocketSession ws = mock(WebSocketSession.class);
                when(ws.getId()).thenReturn("race-" + round);
                ChatConnection connection = new ChatConnection(1L, Instant.now().plusSeconds(60));
                connection.bind(ws);
                connection.allow(room);

                CountDownLatch go = new CountDownLatch(1);
                Future<Boolean> subscribe = pool.submit(() -> {
                    go.await();
                    return connection.addSubscription("sub", room, rooms);
                });
                Future<?> revoke = pool.submit(() -> {
                    go.await();
                    return connection.revoke(room, rooms);
                });
                go.countDown();
                subscribe.get(10, TimeUnit.SECONDS);
                revoke.get(10, TimeUnit.SECONDS);

                assertThat(connection.isAllowed(room)).isFalse();
                assertThat(rooms.sessions(room)).as("라운드 %d", round).doesNotContain("race-" + round);
                assertThat(connection.subscribedRooms()).doesNotContain(room);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
