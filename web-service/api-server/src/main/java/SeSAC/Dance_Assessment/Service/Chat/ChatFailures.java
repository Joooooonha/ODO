package SeSAC.Dance_Assessment.Service.Chat;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * 저장 실패 중 "다시 해 볼 만한 것"을 가려낸다. 여기 없는 예외는 분류되지 않은 예외다(4.6).
 *
 * <ul>
 *   <li>SQLState {@code 40001} 직렬화 실패, {@code 40P01} 교착 롤백</li>
 *   <li>SQLState {@code 57014} 문장 취소 — 트랜잭션 시간 제한으로 PostgreSQL이 취소한 것.
 *       "느림" 자체는 재시도 조건이 아니다. 시간 제한으로 취소돼 롤백된 것만 실패로 본다</li>
 *   <li>SQLState {@code 08xxx} 연결 오류, 커넥션 풀 대기 시간 초과
 *       ({@link SQLTransientException} 계열)</li>
 *   <li>트랜잭션 시작 자체를 못 함(커넥션을 얻지 못함), 문장 전에 이미 시간 초과</li>
 * </ul>
 *
 * <p>원인 사슬 전체를 본다. Spring·Hibernate가 SQLException을 몇 겹으로 감싸기 때문이다.
 */
final class ChatFailures {

    private static final Set<String> RETRYABLE_STATES = Set.of("40001", "40P01", "57014");

    private ChatFailures() {
    }

    static boolean isRetryable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof TransactionTimedOutException
                    || t instanceof SQLTransientException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (RETRYABLE_STATES.contains(state) || state.startsWith("08")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
