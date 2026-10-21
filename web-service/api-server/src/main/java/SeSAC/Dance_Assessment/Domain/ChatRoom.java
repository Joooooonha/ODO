package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 채팅방. 팀 채팅방은 팀마다 정확히 1개({@code uk_chat_room_team}),
 * DM은 두 사람마다 정확히 1개({@code uk_chat_room_dm_pair})다.
 *
 * <p><b>{@code lastSeq}는 이 방에 마지막으로 부여한 메시지 번호다.</b> 메시지 저장은
 * 이 행을 UPDATE로 잠그고 번호를 하나 올린다({@code MessageWriter}). 그래서 이 엔티티로
 * {@code lastSeq}를 고치면 안 된다 — 번호는 그 한 문장에서만 바뀐다.
 *
 * <p><b>{@code BaseTimeEntity}를 상속하지 않는다.</b> 그쪽은 서버(JVM) 시계로 찍는
 * {@code LocalDateTime}인데, 채팅 시각은 {@code timestamptz}에 DB 시계로 찍는다.
 * 서버가 2대 이상이어도 시계가 하나여야 "최근에 바뀐 것"을 서버끼리 같은 기준으로 본다.
 * 그래서 시각 칸은 JPA가 쓰지 않는다(DB 기본값·명시적 UPDATE).
 */
@Entity
@Getter
@Table(
        name = "chat_room",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_room_team", columnNames = "team_id"),
                @UniqueConstraint(name = "uk_chat_room_dm_pair", columnNames = {"dm_user_low", "dm_user_high"})
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatRoomType type;

    /** 팀 채팅방만. */
    @Column(name = "team_id")
    private Long teamId;

    /** DM만. 두 사람 중 작은 id. */
    @Column(name = "dm_user_low")
    private Long dmUserLow;

    /** DM만. 두 사람 중 큰 id. */
    @Column(name = "dm_user_high")
    private Long dmUserHigh;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "modified_at", nullable = false, insertable = false, updatable = false)
    private Instant modifiedAt;

    private ChatRoom(ChatRoomType type, Long teamId) {
        this.type = type;
        this.teamId = teamId;
    }

    public static ChatRoom team(Long teamId) {
        return new ChatRoom(ChatRoomType.TEAM, teamId);
    }

    public boolean isDm() {
        return type == ChatRoomType.DM;
    }
}
