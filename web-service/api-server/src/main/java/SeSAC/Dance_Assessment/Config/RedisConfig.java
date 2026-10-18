package SeSAC.Dance_Assessment.Config;

import io.lettuce.core.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis — 채팅 서버 간 전달(Pub/Sub)에만 쓴다. 영속 데이터는 없다.
 *
 * <p>연결은 둘로 나뉜다: 듣기 전용({@link RedisMessageListenerContainer}, 구독 모드 연결은 PUBLISH를
 * 못 보낸다)과 발행·일반 명령({@code StringRedisTemplate}, Boot 자동 설정).
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 연결이 끊긴 동안 들어온 명령은 쌓아 두지 말고 즉시 거절한다. Lettuce 기본값은 쌓아 두고
     * 명령 시간 제한(기본 60초, 여기서는 500ms)까지 기다리게 해서, Redis가 멈추면 발행 한 건이 메시지
     * 처리 스레드를 붙잡는다. Boot가 만드는 나머지 옵션(시간 제한 활성화 등)은 그대로 둔다.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    /**
     * 받은 메시지를 넘기는 실행기는 <b>스레드 하나</b>다. 기본값({@code SimpleAsyncTaskExecutor})은
     * 메시지마다 스레드를 새로 만들어서 스레드가 계속 생겼다 사라지고, Redis가 보낸 순서도 서버 안에서
     * 섞인다. 넘기는 일은 메모리 브로커에 건네기뿐이라 하나로 충분하다.
     */
    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        ThreadPoolTaskExecutor dispatcher = new ThreadPoolTaskExecutor();
        dispatcher.setCorePoolSize(1);
        dispatcher.setMaxPoolSize(1);
        dispatcher.setThreadNamePrefix("redis-dispatch-");
        dispatcher.initialize();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(dispatcher);
        // 제어 채널은 ControlChannelListener가 켜질 때 등록한다(모든 서버). 덕분에 컨테이너가 처음부터
        // 듣는 상태라, 방 채널 구독은 늘 "듣는 중에 채널 추가" 경로를 탄다.
        return container;
    }

    /**
     * <b>기동할 때 Redis에 PING을 보내고, 실패하면 기동을 멈춘다.</b>
     *
     * <p>배포 기동 확인({@code /api/push/public-key})은 Redis를 건드리지 않는다. 모든 실시간 전달이
     * Redis를 거치므로, Redis가 안 붙은 채 뜨면 저장은 되는데 아무에게도 전달되지 않는 상태로 배포가
     * "성공"한다. 여기서 실패하면 프로세스가 끝나고 → 기동 확인이 실패하고 → 기존 자동 롤백이 동작한다.
     *
     * <p><b>위치가 중요하다.</b> 싱글턴 초기화가 끝날 때(이 콜백) 실행되는데, Tomcat은 그 뒤
     * {@code finishRefresh} 단계에서야 포트를 연다(spring-boot 소스 확인). {@code ApplicationRunner}에서
     * 하면 포트가 열린 뒤라 실패 직전에 기동 확인이 200을 받을 수 있다.
     */
    @Bean
    SmartInitializingSingleton redisStartupPing(RedisConnectionFactory connectionFactory, RedisProperties properties) {
        return () -> {
            // 운영 설정은 ${REDIS_PASSWORD}처럼 기본값 없는 자리 표시자다. 그런데 @ConfigurationProperties
            // 바인딩은 해석하지 못한 자리 표시자를 실패시키지 않고 글자 그대로 남긴다(spring-boot
            // PropertySourcesPlaceholdersResolver 확인). 그대로 두면 "${REDIS_PASSWORD}"를 비밀번호로 보내
            // AUTH 실패로 보이므로, 무엇이 빠졌는지 밝히고 멈춘다.
            requireResolved("spring.data.redis.host", properties.getHost());
            requireResolved("spring.data.redis.password", properties.getPassword());
            try (RedisConnection connection = connectionFactory.getConnection()) {
                String pong = connection.ping();
                log.info("[Redis 기동 확인] {}:{} → {}", properties.getHost(), properties.getPort(), pong);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Redis에 연결할 수 없어 기동을 멈춥니다 ("
                        + properties.getHost() + ":" + properties.getPort() + "): " + e.getMessage(), e);
            }
        };
    }

    private static void requireResolved(String property, String value) {
        if (value != null && value.contains("${")) {
            throw new IllegalStateException(property + " 값이 설정되지 않았습니다(" + value
                    + "). 운영은 /etc/dance-api.env에 해당 환경 변수를 넣어야 합니다.");
        }
    }
}
