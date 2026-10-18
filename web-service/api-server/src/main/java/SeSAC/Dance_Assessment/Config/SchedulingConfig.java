package SeSAC.Dance_Assessment.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 주기 작업(토큰 만료 점검, 지표 요약 로그, 권한 재확인).
 *
 * <p><b>이름이 {@code taskScheduler}인 전용 스케줄러를 둔다.</b> 없으면 웹소켓 설정이 만드는
 * {@code messageBrokerTaskScheduler}가 유일한 스케줄러라 {@code @Scheduled}가 그것을 쓴다(Spring은
 * 스케줄러를 타입으로 먼저 찾고, 여럿일 때 이 이름으로 찾는다). 그러면 DB를 읽는 주기 작업이 하트비트를
 * 보내는 스레드를 붙잡을 수 있다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("chat-sched-");
        return scheduler;
    }
}
