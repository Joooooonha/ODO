package SeSAC.Dance_Assessment.Config;

import SeSAC.Dance_Assessment.Service.Chat.ReadAggregator;
import SeSAC.Dance_Assessment.Service.Chat.ReadFaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 측정 전용 장애 주입 끝점(10/20 작업 5-3·5-4). <b>loadtest 프로필에서만 빈이 생긴다</b>. 관리 포트(9091)는 측정
 * compose 네트워크 안에서만 열리고, 노출 목록(application-loadtest.properties)에도 측정에서만 넣는다.
 *
 * <pre>
 * POST /actuator/loadtest {"action":"read-fault","seconds":10}   읽음 보고에 10초 동안 503
 * POST /actuator/loadtest {"action":"read-drop-once"}            모아 둔 읽음 값을 다음 주기에 한 번 버린다
 * </pre>
 */
@Slf4j
@Component
@Profile("loadtest")
@Endpoint(id = "loadtest")
public class LoadtestFaultEndpoint {

    private final ReadFaults faults;
    private final ReadAggregator aggregator;

    public LoadtestFaultEndpoint(ReadFaults faults, ReadAggregator aggregator) {
        this.faults = faults;
        this.aggregator = aggregator;
    }

    @WriteOperation
    public Map<String, Object> act(String action, @Nullable Integer seconds) {
        long at = System.currentTimeMillis();
        switch (action) {
            case "read-fault" -> faults.failReadsFor((seconds == null ? 10 : seconds) * 1000L);
            case "read-drop-once" -> aggregator.dropNextCycle();
            default -> {
                return Map.of("ok", false, "error", "unknown action " + action);
            }
        }
        log.warn("[측정 전용 장애 주입] action={} seconds={} at={}", action, seconds, at);
        return Map.of("ok", true, "action", action, "at", at);
    }
}
