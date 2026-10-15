package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.AuthProvider;
import SeSAC.Dance_Assessment.Domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * [NEW] 로그인 아이디로 사용자 조회
     */
    Optional<User> findByLoginId(String loginId);

    /**
     * 소셜 로그인 계정 조회.
     *
     * <p>이메일이 아니라 (공급자, 공급자 발급 id) 조합을 키로 쓴다 —
     * 이메일은 동의 여부에 따라 못 받을 수 있지만 id는 항상 오고 바뀌지 않는다.
     */
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
