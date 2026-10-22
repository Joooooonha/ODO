package SeSAC.Dance_Assessment.Service.Chat;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 측정 전용 장애 주입. 켜는 길은 loadtest 프로필의 actuator 끝점({@code LoadtestFaultEndpoint})뿐이라
 * 운영에서는 늘 꺼져 있다. <b>빈도 loadtest 프로필에서만 생긴다</b>(10/21 병합 준비) — 다른 프로필에서는 ChatReadService가
 * 이 검사를 건너뛴다. 켜져 있는 동안 읽음 보고는 DB에 가기 전에 503({@code READ_UNAVAILABLE}).
 */
@Component
@Profile("loadtest")
public class ReadFaults {

    private volatile long readFailUntilMs;

    public void failReadsFor(long millis) {
        readFailUntilMs = System.currentTimeMillis() + millis;
    }

    public boolean readFailing() {
        long until = readFailUntilMs;
        return until != 0 && System.currentTimeMillis() < until;
    }
}
