package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_users_provider_provider_id",
                columnNames = {"provider", "providerId"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 안정성을 위해 코드 추천 받음 -> 기본 생성자 접근 제한
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    // users.team_id는 매핑하지 않는다. 한 사용자가 여러 팀에 속하므로 소속은
    // TeamMember가 갖는다. 컬럼은 롤백된 직전 jar를 위해 남겨 두고 다음 릴리스에서 지운다.

    private String loginId;

    /**
     * <b>개발용 로컬 계정에만 값이 있다.</b> 소셜 로그인 계정은 항상 null이다.
     *
     * <p>예전에는 평문으로 저장하고 평문으로 비교했다. 지금은 BCrypt 해시만 담기며,
     * 운영 프로파일에서는 로컬 로그인 자체를 막으므로 이 컬럼이 채워지지 않는다.
     */
    private String password;

    private String nickname;

    // ===== 소셜 로그인 =====
    @Enumerated(EnumType.STRING)
    private AuthProvider provider;

    /**
     * 공급자가 발급한 고유 식별자(카카오 회원번호, 네이버 id).
     *
     * <p>이메일을 키로 쓰지 않는 이유: 카카오는 이메일 제공에 비즈앱 전환이나
     * 검수가 필요할 수 있고, 사용자가 동의를 거부할 수도 있다. 반면 고유 id는
     * 항상 오고 바뀌지 않는다.
     */
    private String providerId;

    @Builder
    public User(String loginId, String nickname, String password,
                AuthProvider provider, String providerId) {
        this.loginId = loginId;
        this.nickname = nickname;
        this.password = password;
        this.provider = provider != null ? provider : AuthProvider.LOCAL;
        this.providerId = providerId;
    }

    /**
     * 소셜 로그인 계정을 만든다.
     *
     * <p>{@code loginId}는 사람이 입력하는 값이 아니라 내부 식별자다.
     * 공급자별 id가 서로 충돌할 수 있으므로 접두사를 붙인다.
     */
    public static User ofSocial(AuthProvider provider, String providerId, String nickname) {
        return User.builder()
                .provider(provider)
                .providerId(providerId)
                .loginId(provider.name().toLowerCase() + "_" + providerId)
                .nickname(nickname)
                .build();
    }

    /**
     * 비밀번호 해시를 설정한다.
     *
     * <p><b>평문을 넣지 말 것.</b> 호출자가 반드시 {@code PasswordEncoder}로
     * 인코딩한 값을 넘긴다.
     */
    public void setEncodedPassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** 공급자가 닉네임을 바꿨을 때 따라간다. */
    public void updateNickname(String nickname) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
    }
}
