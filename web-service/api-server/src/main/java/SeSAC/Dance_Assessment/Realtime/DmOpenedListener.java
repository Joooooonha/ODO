package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.DmOpenedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DM 방이 커밋된 뒤 두 사람에게 GRANTED를 발행한다(7.3). 상대는 재연결 없이 새 방을 구독하고, 만든 사람의
 * 다른 탭도 안다. 만든 사람의 지금 탭은 응답으로 방 id를 받아 바로 구독한다(GRANTED보다 먼저 오면
 * 구독 확인이 DB로 한 번 더 본다).
 */
@Component
@RequiredArgsConstructor
public class DmOpenedListener {

    private final ControlEventPublisher controlEvents;

    @TransactionalEventListener
    public void onDmOpened(DmOpenedEvent event) {
        controlEvents.granted(event.userLow(), event.roomId());
        controlEvents.granted(event.userHigh(), event.roomId());
    }
}
