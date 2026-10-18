package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.Realtime.ChatMetricsReporter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/** 실시간 전달 설정이 조용히 바뀌지 않게 고정한다. */
@SpringBootTest
@ActiveProfiles("dev")
class ChatRealtimeConfigTest {

    @Autowired
    @Qualifier("clientInboundChannelExecutor")
    Executor inbound;

    @Autowired
    @Qualifier("clientOutboundChannelExecutor")
    Executor outbound;

    @Autowired
    @Qualifier("analysisExecutor")
    Executor analysis;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ChatMetricsReporter reporter;

    @Test
    @DisplayName("[버그 고정] STOMP 채널은 영상 분석 실행기(동시 1, 대기열 20)가 아니라 전용 실행기를 쓴다 (Spring 기본 크기 CPU × 2)")
    void stompChannelsDoNotUseAnalysisExecutor() {
        assertThat(inbound).isNotSameAs(analysis);
        assertThat(outbound).isNotSameAs(analysis);
        ThreadPoolTaskExecutor in = (ThreadPoolTaskExecutor) inbound;
        ThreadPoolTaskExecutor out = (ThreadPoolTaskExecutor) outbound;
        assertThat(in.getThreadNamePrefix()).isEqualTo("clientInboundChannel-");
        assertThat(out.getThreadNamePrefix()).isEqualTo("clientOutboundChannel-");
        assertThat(in.getCorePoolSize()).isEqualTo(Runtime.getRuntime().availableProcessors() * 2);
    }

    @Test
    @DisplayName("[측정 준비] 커넥션 풀·인바운드 실행기 지표가 자동으로 붙고, 요약 로그에 값이 나온다")
    void metricsAreBound() {
        assertThat(meterRegistry.find("hikaricp.connections.acquire").timer()).isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.pending").gauge()).isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.active").gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.active").tag("name", "clientInboundChannelExecutor").gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.queued").tag("name", "clientInboundChannelExecutor").gauge()).isNotNull();

        String summary = reporter.summary();
        assertThat(summary).contains("풀 활성=").doesNotContain("풀 활성=-").doesNotContain("인바운드 활성=-");
    }
}
