package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 채팅방과 사용자의 관계. 한 사람-한 방에 행은 하나다({@code uk_chat_room_member_room_user}).
 * 재가입은 새 행이 아니라 이 행을 다시 ACTIVE로 만든다.
 *
 * <p><b>상태는 이 엔티티로 바꾸지 않는다.</b> 상태를 바꾸는 문장은
 * {@code ChatRoomMemberRepository}의 UPDATE/UPSERT뿐이고, 모두 {@code modified_at = now()}를
 * 직접 넣는다. 서버마다 5초마다 이 칸으로 "최근에 바뀐 멤버"를 찾는데, JPA 감사 기능은
 * 벌크 UPDATE에서 동작하지 않고 서버 시계를 쓴다.
 *
 * <p>{@code lastReadSeq}는 이번 범위에서는 초기값(가입 시점의 방 {@code last_seq})만 쓴다.
 */
@Entity
@Getter
@Table(
        name = "chat_room_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_room_member_room_user",
                columnNames = {"room_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatMemberStatus status;

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "modified_at", nullable = false, insertable = false, updatable = false)
    private Instant modifiedAt;

    private ChatRoomMember(Long roomId, Long userId, long lastReadSeq) {
        this.roomId = roomId;
        this.userId = userId;
        this.status = ChatMemberStatus.ACTIVE;
        this.lastReadSeq = lastReadSeq;
    }

    /** 방이 막 생길 때(팀 생성)의 첫 멤버. */
    public static ChatRoomMember active(Long roomId, Long userId, long lastReadSeq) {
        return new ChatRoomMember(roomId, userId, lastReadSeq);
    }
}
