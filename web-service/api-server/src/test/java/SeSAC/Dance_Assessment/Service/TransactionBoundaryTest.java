package SeSAC.Dance_Assessment.Service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클래스 기본값이 {@code @Transactional(readOnly = true)}인 서비스에서,
 * 쓰기를 하는 메서드가 <b>쓰기 트랜잭션</b>으로 실행되는지 확인한다.
 *
 * <p><b>왜 이런 테스트가 필요한가.</b> 9/9에 {@code VideoService}의 {@code @Transactional}과
 * {@code createUploadUrl} 사이에 다른 메서드가 끼어들어, 애너테이션이 엉뚱한 메서드로
 * 옮겨갔다. {@code createUploadUrl}은 읽기 전용 트랜잭션에서 INSERT를 하게 됐고,
 * 운영(PostgreSQL)은 이를 거부해 업로드가 500이 됐다. 그런데 <b>테스트 DB인 H2는
 * 읽기 전용 트랜잭션의 INSERT를 그대로 받아준다</b> — API를 호출하는 통합 테스트로는
 * 이 실수를 잡을 수 없다.
 *
 * <p>그래서 동작이 아니라 <b>Spring이 실제로 해석하는 트랜잭션 속성</b>을 본다.
 * 메서드 단위 애너테이션이 빠지거나 다른 메서드로 옮겨가면 여기서 실패한다.
 */
class TransactionBoundaryTest {

    private final AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

    static Stream<Arguments> writeMethods() {
        return Stream.of(
                Arguments.of(VideoService.class, "createUploadUrl"),
                Arguments.of(VideoService.class, "completeUpload"),
                Arguments.of(VideoService.class, "deleteVideo"),
                Arguments.of(UserService.class, "signup"),
                Arguments.of(PracticeLogService.class, "requestAnalysis"),
                Arguments.of(PracticeLogService.class, "updateTrimRange"),
                Arguments.of(TeamService.class, "createTeam"),
                Arguments.of(TeamService.class, "join"),
                Arguments.of(TeamService.class, "leave"),
                Arguments.of(TeamService.class, "kick"));
    }

    @ParameterizedTest(name = "{0}.{1}는 쓰기 트랜잭션")
    @MethodSource("writeMethods")
    void writeMethodsAreNotReadOnly(Class<?> service, String methodName) {
        Method method = Arrays.stream(service.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(service.getSimpleName() + "." + methodName + " 없음"));

        TransactionAttribute attribute = source.getTransactionAttribute(method, service);

        assertThat(attribute).as("트랜잭션이 걸려 있어야 한다").isNotNull();
        assertThat(attribute.isReadOnly())
                .as("%s.%s는 DB에 쓰므로 읽기 전용이면 안 된다 (클래스 기본값 readOnly를 덮어써야 한다)",
                        service.getSimpleName(), methodName)
                .isFalse();
    }
}
