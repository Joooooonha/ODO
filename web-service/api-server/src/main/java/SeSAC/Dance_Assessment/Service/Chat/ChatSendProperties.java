package SeSAC.Dance_Assessment.Service.Chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 메시지 저장 재시도 설정({@code chat.send.*}).
 *
 * <p><b>한 메시지가 인바운드 스레드를 붙잡는 시간에 상한을 두기 위한 값들이다(4.5).</b>
 * 인바운드 스레드가 모두 묶이면 클라이언트 하트비트도 같은 대기열에서 밀리고, 메모리 브로커가
 * 30초 무신호로 정상 클라이언트를 끊는다. 그래서 재시도는 횟수만이 아니라 시간으로도 끊는다.
 *
 * @param maxAttempts  최초 1회를 포함한 최대 시도 수
 * @param retryBackoff n번째 재시도 전에 기다리는 시간
 * @param retryCutoff  처음 시도를 시작한 뒤 이 시간이 지났으면 새 시도를 시작하지 않는다.
 *                     최악 처리 시간 = 이 값 + 마지막 대기 + 시도 1회의 최악(커넥션 대기 +
 *                     트랜잭션 시간 제한)
 */
@ConfigurationProperties("chat.send")
public record ChatSendProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue({"100ms", "200ms", "400ms"}) List<Duration> retryBackoff,
        @DefaultValue("3s") Duration retryCutoff) {

    Duration backoffBefore(int retryNumber) {
        int i = Math.min(retryNumber - 1, retryBackoff.size() - 1);
        return retryBackoff.get(i);
    }
}
