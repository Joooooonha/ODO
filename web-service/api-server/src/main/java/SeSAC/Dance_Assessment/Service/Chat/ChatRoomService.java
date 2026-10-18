package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Common.ApiException;
import SeSAC.Dance_Assessment.Common.ErrorCode;
import SeSAC.Dance_Assessment.Domain.ChatMemberStatus;
import SeSAC.Dance_Assessment.Domain.ChatRoom;
import SeSAC.Dance_Assessment.Domain.ChatRoomMember;
import SeSAC.Dance_Assessment.Dto.Chat.ChatMessagePageResponse;
import SeSAC.Dance_Assessment.Dto.Chat.ChatMessageResponse;
import SeSAC.Dance_Assessment.Dto.Chat.ChatRoomSummaryResponse;
import SeSAC.Dance_Assessment.Dto.Chat.DmOpenResponse;
import SeSAC.Dance_Assessment.Infrastructure.ChatQueries;
import SeSAC.Dance_Assessment.Infrastructure.ChatRoomMemberRepository;
import SeSAC.Dance_Assessment.Infrastructure.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방과 채팅 멤버. 팀 쪽 연결(생성·참여·재활성화·나가기·추방)은 {@code TeamService}가
 * <b>같은 트랜잭션에서 직접 호출</b>한다(이벤트가 아니다). 같은 DB라 장애 격리 이득이 작고,
 * 이벤트로 하면 보정 작업·중간 상태·멤버 동기화가 생긴다.
 *
 * <p><b>잠금 순서: {@code team_member} → {@code chat_room} → {@code chat_room_member} → {@code message}.</b>
 * 팀 경로는 {@code team_member}를 먼저 잠근 뒤 여기서 방 행을 잠근다. 메시지 저장은 방 행만
 * 잠그고 멤버는 읽기만 한다. 모든 경로가 이 순서를 지키므로 교착이 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    static final int MAX_PAGE = 50;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatQueries chatQueries;

    // ===== 팀 연결 (TeamService의 트랜잭션 안에서 호출) =====

    /**
     * 팀 채팅방과 팀장의 채팅 멤버를 만든다. 실패하면 팀 생성도 실패한다(같은 트랜잭션).
     * 그 대가로 원인을 분명히 남긴다.
     */
    @Transactional
    public Long createTeamRoom(Long teamId, Long leaderId) {
        try {
            ChatRoom room = chatRoomRepository.save(ChatRoom.team(teamId));
            chatRoomMemberRepository.save(ChatRoomMember.active(room.getId(), leaderId, 0));
            return room.getId();
        } catch (RuntimeException e) {
            log.error("[팀 채팅방 생성 실패] 팀 생성도 롤백된다 teamId={} leaderId={}", teamId, leaderId, e);
            throw e;
        }
    }

    /**
     * 팀 참여(신규)·재활성화. 방 행 잠금 아래에서 {@code last_seq}를 읽어 읽은 위치로 둔다
     * (재가입도 예전 값을 되살리지 않는다).
     *
     * @return 팀 채팅방 id
     */
    @Transactional
    public Long joinTeamRoom(Long teamId, Long userId) {
        ChatRoom room = lockTeamRoom(teamId);
        chatRoomMemberRepository.activate(room.getId(), userId, room.getLastSeq());
        return room.getId();
    }

    /**
     * 나가기(LEFT)·추방(KICKED). 방 행을 먼저 잠가서 진행 중인 메시지 저장과 한 줄로 선다 —
     * 이 트랜잭션이 커밋된 뒤의 전송은 반드시 거부된다.
     *
     * @return 팀 채팅방 id
     */
    @Transactional
    public Long leaveTeamRoom(Long teamId, Long userId, ChatMemberStatus status) {
        if (status == ChatMemberStatus.ACTIVE) {
            throw new IllegalArgumentException("나가기·추방 상태만 받는다: " + status);
        }
        ChatRoom room = lockTeamRoom(teamId);
        int changed = chatRoomMemberRepository.deactivate(room.getId(), userId, status.name());
        if (changed != 1) {
            // 팀 멤버는 ACTIVE였는데 채팅 멤버가 아니었다 — 둘이 어긋났다는 뜻이다.
            log.warn("[채팅 멤버 불일치] 활동 중 채팅 멤버가 없어 상태를 바꾸지 못함 teamId={} roomId={} userId={} status={}",
                    teamId, room.getId(), userId, status);
        }
        return room.getId();
    }

    /** Team Space가 채팅 탭에 쓰는 팀 채팅방 id. 팀 멤버 확인은 호출한 쪽이 한다. */
    public Long getTeamRoomId(Long teamId) {
        return chatRoomRepository.findByTeamId(teamId)
                .map(ChatRoom::getId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방이 없음: teamId=" + teamId));
    }

    private ChatRoom lockTeamRoom(Long teamId) {
        return chatRoomRepository.findByTeamIdForUpdate(teamId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방이 없음: teamId=" + teamId));
    }

    // ===== DM =====

    /**
     * DM 방을 열거나 만든다.
     *
     * <ul>
     *   <li>이미 있으면 그 방 — 지금 같은 팀이 아니어도 된다(예전 대화는 계속 쓴다)</li>
     *   <li>없으면 지금 같은 팀(둘 다 ACTIVE)일 때만 만든다. 아니면 403</li>
     *   <li>동시에 서로 열어도 {@code uk_chat_room_dm_pair}로 방은 하나, 두 응답의 방 id가 같다</li>
     * </ul>
     */
    @Transactional
    public DmOpenResponse openDm(Long me, Long peer) {
        if (peer == null) {
            throw new IllegalArgumentException("상대를 지정해주세요.");
        }
        if (me.equals(peer)) {
            throw new ApiException(ErrorCode.CANNOT_DM_SELF);
        }
        long low = Math.min(me, peer);
        long high = Math.max(me, peer);

        Optional<Long> existing = chatQueries.findDmRoom(low, high);
        if (existing.isPresent()) {
            return new DmOpenResponse(existing.get(), false);
        }
        if (!chatQueries.shareActiveTeam(me, peer)) {
            throw new ApiException(ErrorCode.DM_NOT_ALLOWED);
        }

        Optional<Long> created = chatQueries.insertDmRoom(low, high);
        if (created.isEmpty()) {
            // 상대가 같은 순간 먼저 만들었다. 그 트랜잭션이 커밋된 뒤라 이제 보인다.
            Long roomId = chatQueries.findDmRoom(low, high)
                    .orElseThrow(() -> new IllegalStateException("DM 방 충돌 뒤 방이 보이지 않음"));
            return new DmOpenResponse(roomId, false);
        }
        chatQueries.insertDmMember(created.get(), low);
        chatQueries.insertDmMember(created.get(), high);
        // 상대의 접속 세션에 새 방을 알리는 GRANTED는 권한·차단 단계(2-3)에서 붙인다.
        return new DmOpenResponse(created.get(), true);
    }

    // ===== 조회 =====

    public List<ChatRoomSummaryResponse> getMyRooms(Long me) {
        return chatQueries.findMyRooms(me);
    }

    /**
     * 메시지 조회. 파라미터 없음 → 최신 {@value #MAX_PAGE}개 / {@code afterSeq} → 그 이후 /
     * {@code beforeSeq} → 그 이전. 항상 오름차순.
     *
     * <p>ACTIVE 멤버가 아니면(방이 없어도) 403이다. 방 번호만으로 방이 있는지 알아낼 수 없게 한다.
     */
    public ChatMessagePageResponse getMessages(Long me, Long roomId, Long afterSeq, Long beforeSeq, Integer limit) {
        if (afterSeq != null && beforeSeq != null) {
            throw new IllegalArgumentException("afterSeq와 beforeSeq는 함께 쓸 수 없습니다.");
        }
        int size = limit == null ? MAX_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) {
            throw new IllegalArgumentException("limit은 1~" + MAX_PAGE + " 사이여야 합니다.");
        }
        requireActiveMember(roomId, me);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_CHAT_MEMBER));

        List<ChatMessageResponse> messages;
        if (afterSeq != null) {
            messages = chatQueries.findAfter(roomId, afterSeq, size, me);
        } else if (beforeSeq != null) {
            messages = chatQueries.findBefore(roomId, beforeSeq, size, me);
        } else {
            messages = chatQueries.findLatest(roomId, size, me);
        }
        return new ChatMessagePageResponse(roomId, room.getLastSeq(), messages);
    }

    private void requireActiveMember(Long roomId, Long userId) {
        chatRoomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .filter(m -> m.getStatus() == ChatMemberStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_CHAT_MEMBER));
    }
}
