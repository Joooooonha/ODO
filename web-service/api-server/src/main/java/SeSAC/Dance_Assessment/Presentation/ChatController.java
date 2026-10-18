package SeSAC.Dance_Assessment.Presentation;

import SeSAC.Dance_Assessment.Dto.Chat.ChatMessagePageResponse;
import SeSAC.Dance_Assessment.Dto.Chat.ChatRoomSummaryResponse;
import SeSAC.Dance_Assessment.Dto.Chat.DmOpenRequest;
import SeSAC.Dance_Assessment.Dto.Chat.DmOpenResponse;
import SeSAC.Dance_Assessment.Dto.Chat.ReadReportRequest;
import SeSAC.Dance_Assessment.Dto.Chat.ReadStateResponse;
import SeSAC.Dance_Assessment.Security.CurrentUserId;
import SeSAC.Dance_Assessment.Service.Chat.ChatReadService;
import SeSAC.Dance_Assessment.Service.Chat.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 채팅 HTTP API. 모두 로그인이 필요하다. 메시지 전송은 여기가 아니라 웹소켓(STOMP)으로 한다.
 *
 * <p>오류는 {@code {error, code}}로 응답한다. 403 {@code NOT_CHAT_MEMBER}(방이 없어도 같은 응답) /
 * 403 {@code DM_NOT_ALLOWED} / 400 {@code CANNOT_DM_SELF}·{@code INVALID_READ_SEQ}·{@code READ_SEQ_AHEAD}·잘못된 파라미터.
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatRoomService chatRoomService;
    private final ChatReadService chatReadService;

    /** 내 방 목록. 팀 방과 DM을 한 목록에 최근 순으로. 메시지 없는 DM은 {@code empty: true}(화면이 숨긴다). */
    @GetMapping("/chat/rooms")
    public ResponseEntity<List<ChatRoomSummaryResponse>> getMyRooms(@CurrentUserId Long userId) {
        return ResponseEntity.ok(chatRoomService.getMyRooms(userId));
    }

    /**
     * 메시지 조회. 없음 → 최신 50개 / {@code afterSeq=N} → N 이후 / {@code beforeSeq=N} → N 이전.
     * {@code limit}은 1~50. 항상 오름차순.
     */
    @GetMapping("/chat/rooms/{roomId}/messages")
    public ResponseEntity<ChatMessagePageResponse> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Integer limit,
            @CurrentUserId Long userId) {
        return ResponseEntity.ok(chatRoomService.getMessages(userId, roomId, afterSeq, beforeSeq, limit));
    }

    /** DM 열기. 이미 있으면 그 방, 없으면 같은 팀일 때만 새로 만든다. */
    @PostMapping("/chat/dms")
    public ResponseEntity<DmOpenResponse> openDm(
            @CurrentUserId Long userId,
            @RequestBody DmOpenRequest request) {
        return ResponseEntity.ok(chatRoomService.openDm(userId, request.userId()));
    }

    /**
     * 읽음 보고 {@code {seq}} → 204. 사용자는 쿠키의 인증 정보에서만 꺼낸다(본문의 다른 칸은 버린다).
     * 위치가 올라갔을 때만 방 전원에게 읽음 스냅샷이 나간다.
     */
    @PostMapping("/chat/rooms/{roomId}/read")
    public ResponseEntity<Void> reportRead(
            @PathVariable Long roomId,
            @RequestBody ReadReportRequest request,
            @CurrentUserId Long userId) {
        chatReadService.report(userId, roomId, request.seq());
        return ResponseEntity.noContent().build();
    }

    /** 방의 읽음 상태 {@code {version, positions, ranges}}. 방을 열 때·재연결할 때(구독 → SYNC 뒤). */
    @GetMapping("/chat/rooms/{roomId}/read-state")
    public ResponseEntity<ReadStateResponse> getReadState(@PathVariable Long roomId, @CurrentUserId Long userId) {
        return ResponseEntity.ok(chatReadService.readState(userId, roomId));
    }
}
