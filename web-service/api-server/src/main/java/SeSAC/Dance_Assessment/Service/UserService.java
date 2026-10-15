package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Domain.User;
import SeSAC.Dance_Assessment.Dto.User.LoginRequest;
import SeSAC.Dance_Assessment.Dto.User.SignupRequest;
import SeSAC.Dance_Assessment.Dto.User.UserResponse;
import SeSAC.Dance_Assessment.Infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬(아이디/비밀번호) 계정 서비스.
 *
 * <p><b>이 경로는 개발 환경 전용이다.</b> 운영에서는 카카오/네이버 소셜 로그인만
 * 쓴다 — 소셜만 두면 로컬 개발할 때마다 실제 소셜 로그인을 거쳐야 해서 불편하므로
 * 남겨둔 것이고, {@code AuthController}가 {@code dev} 프로파일에서만 이 경로를
 * 노출한다. 덕분에 운영 DB에는 비밀번호가 아예 저장되지 않는다.
 *
 * <p>비밀번호는 BCrypt로 해싱한다. 예전에는 평문으로 저장하고 평문으로 비교했다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원가입. 팀 설정은 하지 않는다 — 팀은 TEAM 탭에서만 만들고 참여한다.
     */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        // 1. 아이디 중복 체크
        if (userRepository.findByLoginId(request.loginId()).isPresent()) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }

        // 2. 사용자 생성
        User user = User.builder()
                .loginId(request.loginId())
                .nickname(request.nickname())
                .build();

        // 3. 비밀번호 해싱. 평문은 절대 저장하지 않는다.
        user.setEncodedPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        return UserResponse.from(user);
    }

    /**
     * 로컬 로그인. 성공하면 사용자를 돌려준다(JWT 발급은 컨트롤러가 한다).
     *
     * <p>아이디가 없는 경우와 비밀번호가 틀린 경우에 <b>같은 메시지</b>를 준다.
     * 구분해서 알려주면 어떤 아이디가 존재하는지 확인해볼 수 있게 된다.
     */
    public User login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));

        // 소셜 계정은 비밀번호가 없다. null.equals로 죽지 않게 먼저 막는다.
        if (user.getPassword() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return user;
    }

    /** 로그인 성공 후 응답 변환용. */
    public UserResponse toResponse(User user) {
        return UserResponse.from(user);
    }

    /**
     * [NEW] 현재 로그인된 사용자 정보 조회
     */
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
        return UserResponse.from(user);
    }
}
