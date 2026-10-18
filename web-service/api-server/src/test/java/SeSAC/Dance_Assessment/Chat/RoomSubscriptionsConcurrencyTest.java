package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Realtime.ChatDestinations;
import SeSAC.Dance_Assessment.Realtime.RoomSubscriptions;
import SeSAC.Dance_Assessment.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [11.4·C2] 마지막 구독자 퇴장과 새 구독자 입장이 동시에 반복돼도 "이 서버의 집합에 세션이 있는데
 * Redis 방 채널 구독이 없는" 상태가 생기지 않는다(반대도 없다).
 *
 * <p>방 번호는 실제 방과 겹치지 않는 큰 수를 쓴다. 다른 테스트 컨텍스트도 같은 Redis를 쓰지만 이 방은
 * 이 서버만 구독하므로 NUMSUB는 0 또는 1이다.
 */
@SpringBootTest
@ActiveProfiles("dev")
class RoomSubscriptionsConcurrencyTest {

    @Autowired
    RoomSubscriptions rooms;

    @Test
    @DisplayName("8개 스레드가 같은 방에 입장·퇴장을 동시에 반복(30라운드) → 매 라운드 끝 상태에서 집합이 비어 있지 않음 ⇔ Redis 구독 있음")
    void joinLeaveRaceKeepsRedisInSync() throws Exception {
        long room = 9_000_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
        String channel = ChatDestinations.roomChannel(room).getTopic();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 30; round++) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    String session = "s" + t;
                    // 라운드마다 일부 스레드는 들어간 채로 끝난다(끝 상태가 비었다·안 비었다 둘 다 나오게)
                    boolean stayJoined = ThreadLocalRandom.current().nextInt(4) == 0;
                    futures.add(pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < 20; i++) {
                            rooms.join(room, session);
                            rooms.leave(room, session);
                        }
                        if (stayJoined) {
                            rooms.join(room, session);
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }

                boolean hasSessions = !rooms.sessions(room).isEmpty();
                long redisSubscribers = TestContainers.redisSubscribers(channel);
                assertThat(redisSubscribers)
                        .as("라운드 %d: 세션 %s", round, rooms.sessions(room))
                        .isEqualTo(hasSessions ? 1 : 0);

                for (int t = 0; t < 8; t++) {
                    rooms.leave(room, "s" + t);
                }
                assertThat(TestContainers.redisSubscribers(channel)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
