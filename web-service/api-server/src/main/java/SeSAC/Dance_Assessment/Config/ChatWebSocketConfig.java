package SeSAC.Dance_Assessment.Config;

import SeSAC.Dance_Assessment.Realtime.ChatConnectionRegistry;
import SeSAC.Dance_Assessment.Realtime.ChatHandshakeInterceptor;
import SeSAC.Dance_Assessment.Realtime.ChatInboundInterceptor;
import SeSAC.Dance_Assessment.Realtime.ChatOutboundInterceptor;
import SeSAC.Dance_Assessment.Realtime.ChatWebSocketHandler;
import SeSAC.Dance_Assessment.Realtime.ConnectionCloser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;

/**
 * STOMP over WebSocket (5.1).
 *
 * <ul>
 *   <li>엔드포인트 {@code /api/ws} — 순수 웹소켓(SockJS 없음). 백엔드 경로는 전부 {@code /api} 아래다.
 *       인증은 핸드셰이크에 실리는 쿠키를 기존 Security 필터가 본다. Origin은 CORS와 같은 허용 목록</li>
 *   <li><b>받는 쪽 순서</b>: 세션마다 한 번에 하나씩 처리한다(앞 메시지 처리가 끝나야 다음). 같은
 *       사람이 보낸 순서가 저장 순서가 된다</li>
 *   <li><b>보내는 쪽 순서</b>: 세션마다 보낸 순서대로 내보낸다. 번호 없는 프레임(확정·오류·차단)도
 *       같은 통로라 필요하다</li>
 *   <li>메모리 브로커({@code /topic}, {@code /queue}) — 서버 간 전달은 Redis가 한다.
 *       하트비트 10초/10초. 메모리 브로커의 하트비트는 스케줄러를 지정해야 한다(값만 주면 기동 시
 *       "Heartbeat values configured but no TaskScheduler provided")</li>
 *   <li><b>인바운드·아웃바운드 채널에 전용 실행기를 지정한다.</b> Boot는 {@code AsyncTaskExecutor} 빈이
 *       하나만 보이면 그것을 STOMP 채널 실행기로 쓰는데(spring-boot {@code WebSocketMessagingAutoConfiguration}),
 *       이 앱에서는 먼저 만들어진 {@code analysisExecutor}(동시 실행 1, 대기열 20 — 3분짜리 영상 분석용)가
 *       골라졌다. 채팅 처리가 분석 뒤에 줄을 서고 대기열이 차면 거절된다. 크기는 Spring 기본값(CPU × 2)
 *       그대로다 — 스레드 수를 바꾸는 것은 측정 후 결정(4.5)</li>
 *   <li><b>인바운드(메시지 처리) 스레드 수는 {@code chat.ws.inbound-threads}로 바꿀 수 있다</b>. 0이면 위 기본값
 *       (측정 환경 2 CPU에서 4). 메시지 1건 시간의 대부분이 커밋의 디스크 동기화 대기라, 스레드를 늘리면 동시 커밋이 같은
 *       동기화에 묶인다(그룹 커밋). 세션별 순서는 스레드 수와 무관하다 — {@code setPreserveReceiveOrder}가 세션마다 앞 메시지
 *       처리가 끝나야 다음을 넘긴다</li>
 * </ul>
 *
 * <p>Boot의 설정({@code @Order(0)}) 뒤에 실행돼야 채널 실행기 지정이 이긴다.
 */
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.LOWEST_PRECEDENCE)
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final List<String> allowedOrigins;
    private final int queueLimit;
    private final long heartbeatMs;
    private final int inboundThreads;
    private final ChatHandshakeInterceptor handshakeInterceptor;
    private final ChatInboundInterceptor inboundInterceptor;
    private final ChatOutboundInterceptor outboundInterceptor;
    private final ChatConnectionRegistry connectionRegistry;
    private final ConnectionCloser connectionCloser;

    private TaskScheduler brokerScheduler;

    public ChatWebSocketConfig(
            @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000}")
            List<String> allowedOrigins,
            @Value("${chat.ws.queue-limit:100}") int queueLimit,
            @Value("${chat.ws.heartbeat-ms:10000}") long heartbeatMs,
            @Value("${chat.ws.inbound-threads:0}") int inboundThreads,
            ChatHandshakeInterceptor handshakeInterceptor,
            ChatInboundInterceptor inboundInterceptor,
            ChatOutboundInterceptor outboundInterceptor,
            ChatConnectionRegistry connectionRegistry,
            ConnectionCloser connectionCloser) {
        this.allowedOrigins = allowedOrigins;
        this.queueLimit = queueLimit;
        this.heartbeatMs = heartbeatMs;
        this.inboundThreads = inboundThreads;
        this.handshakeInterceptor = handshakeInterceptor;
        this.inboundInterceptor = inboundInterceptor;
        this.outboundInterceptor = outboundInterceptor;
        this.connectionRegistry = connectionRegistry;
        this.connectionCloser = connectionCloser;
    }

    /** 브로커 스케줄러는 이 설정이 만드는 빈이라 늦게 받는다(Spring 문서의 방식). */
    @Autowired
    public void setBrokerScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler) {
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new))
                .addInterceptors(handshakeInterceptor);
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(brokerScheduler)
                .setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.executor(channelExecutor("clientInboundChannel-", inboundThreads));
        registration.interceptors(inboundInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.executor(channelExecutor("clientOutboundChannel-", 0));
        registration.interceptors(outboundInterceptor);
    }

    /**
     * Spring이 채널 실행기를 지정하지 않았을 때 쓰는 기본값과 같다(코어 = CPU × 2, 대기열·최대 무제한). {@code threads > 0}이면 코어를 그 값으로.
     * 이 객체가 그대로 {@code clientInboundChannelExecutor}/{@code clientOutboundChannelExecutor} 빈이 되어
     * Spring이 초기화·종료한다(여기서 {@code initialize()}를 부르지 않는다).
     */
    private static ThreadPoolTaskExecutor channelExecutor(String threadNamePrefix, int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads > 0 ? threads : Runtime.getRuntime().availableProcessors() * 2);
        executor.setMaxPoolSize(Integer.MAX_VALUE);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(threadNamePrefix);
        return executor;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler ->
                new ChatWebSocketHandler(handler, connectionRegistry, connectionCloser, queueLimit));
    }
}
