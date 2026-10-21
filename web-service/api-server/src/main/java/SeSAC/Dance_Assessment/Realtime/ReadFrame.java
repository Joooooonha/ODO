package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ReadRanges;
import SeSAC.Dance_Assessment.Service.Chat.ReadSnapshot;

import java.util.List;

/**
 * 방 topic으로 나가는 읽음 프레임. 메시지 프레임({@link RoomBroadcast})과 같은 목적지에 오고, 화면은
 * {@code type}으로 나눈다 — 메시지는 {@code TEXT}/{@code SYSTEM}, 읽음은 {@code READ}.
 *
 * <p>Redis로는 {@code positions}만 보낸다({@link ReadPublisher}). {@code ranges}는 받은 서버가 계산해 붙인다.
 */
public record ReadFrame(String type, long roomId, long version,
                        List<ReadSnapshot.Position> positions, List<ReadRanges.Range> ranges) {

    public static final String TYPE = "READ";

    /** Redis 채널 본문(구간 없음). */
    public record Published(String type, long roomId, long version, List<ReadSnapshot.Position> positions) {
    }

    public static Published published(ReadSnapshot snapshot) {
        return new Published(TYPE, snapshot.roomId(), snapshot.version(), snapshot.positions());
    }

    public static ReadFrame of(Published p) {
        return new ReadFrame(TYPE, p.roomId(), p.version(), p.positions(), ReadRanges.of(p.positions()));
    }
}
