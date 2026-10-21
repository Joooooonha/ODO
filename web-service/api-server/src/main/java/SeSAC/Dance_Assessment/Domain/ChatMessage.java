package SeSAC.Dance_Assessment.Domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * 채팅 메시지. 저장 후에는 바뀌지 않는다.
 *
 * <p><b>저장은 이 엔티티로 하지 않는다</b> — {@code MessageWriter}가 방 행을 잠그고 번호를
 * 받는 한 트랜잭션 안에서 SQL로 직접 넣는다. 이 엔티티는 조회와 스키마 검증(validate)용이다.
 *
 * <ul>
 *   <li>{@code uk_message_room_seq}: 방 안 번호는 하나뿐(안전망)</li>
 *   <li>{@code uk_message_sender_client}: 같은 사람의 같은 메시지는 한 번만(재전송)</li>
 *   <li>{@code uk_message_system_client}: 시스템 메시지 중복 방지 — 부분 인덱스라
 *       JPA로 선언할 수 없어 V4 마이그레이션에만 있다</li>
 * </ul>
 *
 * <p>클래스 이름이 {@code Message}가 아닌 것은 Spring의 {@code Message}(STOMP)와 겹치지
 * 않게 하려는 것이다. 테이블 이름은 {@code message}다.
 */
@Entity
@Immutable
@Getter
@Table(
        name = "message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_message_room_seq", columnNames = {"room_id", "seq"}),
                @UniqueConstraint(name = "uk_message_sender_client", columnNames = {"sender_id", "client_message_id"})
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @Column(name = "message_id")
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private long seq;

    /** 시스템 메시지는 null. */
    @Column(name = "sender_id")
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatMessageType type;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
