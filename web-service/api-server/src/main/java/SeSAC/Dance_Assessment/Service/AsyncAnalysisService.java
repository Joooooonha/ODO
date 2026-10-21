package SeSAC.Dance_Assessment.Service;

import SeSAC.Dance_Assessment.Dto.Ai.AiAnalysisResponse;
import SeSAC.Dance_Assessment.Service.AnalysisResultWriter.AnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 비동기 AI 분석 실행기.
 *
 * <p><b>동기 호출은 불가능하다.</b> 분석에 실측 3분 남짓이 걸려 HTTP 요청을
 * 붙잡고 있을 수 없다. 클라이언트는 분석을 시작시킨 뒤
 * {@code GET /practice-logs/{logId}/progress}를 폴링한다.
 *
 * <p><b>이 클래스에는 {@code @Transactional}이 없다.</b> 예전에는 있었고, 그 탓에
 * 분석 서버를 기다리는 3분 내내 트랜잭션이 열려 있었다. 시작 시 기록한 작업
 * 표식이 그동안 커밋되지 않아 진행률 조회가 자기 작업을 알아보지 못했고,
 * 화면은 계속 0%에 머물렀다. DB 쓰기는 전부 {@link AnalysisResultWriter}의
 * 짧은 트랜잭션에 맡기고, 여기서는 오래 걸리는 호출만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAnalysisService {

    /** 결과 이미지를 미리 발급할 슬롯 수 = 돌려받을 상위 구간 수. */
    private static final int TOP_ISSUES = 10;

    private final AnalysisResultWriter writer;
    private final AiClientService aiClientService;
    private final AnalysisNotifier notifier;
    private final AnalysisLock analysisLock;

    /**
     * 별도 스레드에서 분석을 실행한다. 호출자를 블로킹하지 않는다.
     */
    @Async("analysisExecutor")
    public void analyzeAsync(Long practiceLogId) {
        log.info("[비동기 분석 시작] PracticeLog ID: {}", practiceLogId);

        String runId = String.valueOf(System.currentTimeMillis());
        // 이번 실행을 가리키는 표식. 진행 상황을 물어볼 때 "내 작업이 맞는지"
        // 대조하는 데 쓴다 (분석 서버는 진행 상황을 한 건분만 들고 있다).
        String jobId = "log-" + practiceLogId + "-" + runId;

        AnalysisJob job;
        try {
            job = writer.begin(practiceLogId, jobId, runId, TOP_ISSUES);
        } catch (Exception e) {
            log.error("[비동기 분석 준비 실패] PracticeLog ID: {}", practiceLogId, e);
            safeFail(practiceLogId, e.getMessage());
            return;
        }
        if (job == null) {
            return;   // 영상이 갖춰지지 않음 — begin()이 이미 경고를 남겼다
        }

        try {
            // 앱 서버가 여러 대여도 맥미니에는 한 건씩(AnalysisLock). 기다리는 동안 이 작업은 PROCESSING·0%로 보인다
            AiAnalysisResponse response = analysisLock.runExclusive(jobId, () -> aiClientService.requestAnalysis(job, jobId));
            writer.complete(practiceLogId, response, job.imageKeys());

            log.info("[비동기 분석 완료] PracticeLog ID: {}, 지적 구간: {}개",
                    practiceLogId, response.getIssueCount());
            notifier.analysisFinished(practiceLogId, true, response.getIssueCount());

        } catch (Exception e) {
            log.error("[비동기 분석 실패] PracticeLog ID: {}, 에러: {}",
                    practiceLogId, e.getMessage(), e);
            safeFail(practiceLogId, e.getMessage());
            notifier.analysisFinished(practiceLogId, false, null);
        }
    }

    private void safeFail(Long practiceLogId, String reason) {
        try {
            writer.fail(practiceLogId, reason);
        } catch (Exception saveError) {
            log.error("[분석 실패 상태 저장 실패]", saveError);
        }
    }
}
