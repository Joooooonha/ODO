import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCurrentUser, getPracticeLogs } from '../api/client';
import './Dashboard.css';

// 연습실 거울 · 바닥 테이프 마킹 세계관 (surface brief:
// web-service-frontend-src-pages-dashboard-jsx — 방향이 "안무 카운트보드"에서
// 교체됨). 아이콘+제목+설명 카드 그리드 대신, 바닥에 색 테이프로 표시한
// 자리처럼 다음 할 일을 배치한다. 상단 스트립은 "최근 분석" 실데이터로
// 채운다 — critique에서 지적된 "진입 화면에 증거 신호가 없다"는 문제를
// 여기서 없앤다.
export default function Dashboard() {
    const user = getCurrentUser();
    const [recentLog, setRecentLog] = useState(null);
    const [logsLoaded, setLogsLoaded] = useState(false);
    const [hasAnyLog, setHasAnyLog] = useState(true); // 로딩 중엔 첫 사용자 안내가 깜빡이지 않도록 낙관적 기본값

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const data = await getPracticeLogs(0, 1);
                if (cancelled) return;
                const content = data?.content ?? [];
                setRecentLog(content[0] ?? null);
                setHasAnyLog(content.length > 0);
            } catch {
                // 요약 스트립은 없어도 화면이 정상 동작해야 하는 보조 정보다.
            } finally {
                if (!cancelled) setLogsLoaded(true);
            }
        })();
        return () => { cancelled = true; };
    }, []);

    // 라벨은 A/B/C — 실제 순서가 없는 세 목적지라 01/02/03처럼 순번으로
    // 보이는 표기를 쓰지 않는다(craft-floor: 순서 정보가 없는 섹션 번호 금지).
    // 바닥에 A·B·C 구역 테이프를 붙이는 것과 같은 자리 표시 문법이다.
    const marks = [
        {
            number: 'A',
            title: '자유 연습',
            desc: '기준 영상과 연습 영상을 업로드하여 AI 분석을 받아보세요',
            link: '/practice',
        },
        {
            // 여러 팀에 속할 수 있어 "내 팀" 하나를 말할 수 없으므로 문구는 고정이다.
            number: 'B',
            title: '팀',
            desc: '내 팀을 확인하고, 참여 코드로 새 팀에 들어가세요',
            link: '/teams',
        },
        {
            number: 'C',
            title: '내 기록',
            desc: '지금까지의 연습 기록과 분석 결과를 확인하세요',
            link: '/logs',
        },
    ];

    return (
        <div className="dashboard page studio">
            <div className="container">
                <header className="st-header">
                    <h1>안녕하세요, {user?.nickname || '댄서'}님</h1>
                </header>

                {/* ===== 증거 스트립 — 거울에 비친 대형 숫자 ===== */}
                <section className="st-strip">
                    {!logsLoaded ? (
                        <span className="st-strip-status">불러오는 중…</span>
                    ) : recentLog?.status === 'COMPLETED' && recentLog.issueCount != null ? (
                        <>
                            <span className="st-strip-number st-mirror-numeral" data-reflect={recentLog.issueCount}>{recentLog.issueCount}</span>
                            <span className="st-strip-label">
                                최근 분석 · 다듬을 구간
                                <Link to={`/logs/${recentLog.logId}`} className="st-strip-link">자세히 보기</Link>
                            </span>
                        </>
                    ) : recentLog?.status === 'PROCESSING' ? (
                        <span className="st-strip-status">
                            최근 분석이 진행 중이에요
                            <Link to={`/logs/${recentLog.logId}`} className="st-strip-link">보러 가기</Link>
                        </span>
                    ) : recentLog?.status === 'WAITING' ? (
                        <span className="st-strip-status">
                            구간 지정을 기다리는 기록이 있어요
                            <Link to={`/logs/${recentLog.logId}`} className="st-strip-link">이어서 하기</Link>
                        </span>
                    ) : recentLog?.status === 'FAILED' ? (
                        <span className="st-strip-status">
                            최근 분석이 실패했어요
                            <Link to={`/logs/${recentLog.logId}`} className="st-strip-link">다시 시도하기</Link>
                        </span>
                    ) : (
                        <span className="st-strip-status">첫 분석을 기다리는 빈 자리예요</span>
                    )}
                </section>

                {/* ===== 다음 할 일 — 바닥에 붙인 테이프 마크 ===== */}
                <section className="st-marks">
                    {marks.map((mark) => (
                        <Link key={mark.number} to={mark.link} className="st-mark">
                            <span className="st-mark-number">{mark.number}</span>
                            <span className="st-mark-title">{mark.title}</span>
                            <span className="st-mark-desc">{mark.desc}</span>
                        </Link>
                    ))}
                </section>

                {/* 첫 기록이 없는 사용자에게만 — 코치가 거울에 붙여둔 메모처럼 */}
                {logsLoaded && !hasAnyLog && (
                    <section className="st-guide">
                        <h2>처음이신가요?</h2>
                        <ol className="st-guide-list">
                            <li>따라 할 <b>기준 영상</b>을 준비하세요.</li>
                            <li>기준 영상을 보고 따라 춘 <b>연습 영상</b>을 촬영하세요.</li>
                            <li>두 영상을 올리면 AI가 다듬을 구간을 짚어드려요.</li>
                        </ol>
                        <Link to="/practice" className="st-guide-cta">자유 연습 시작하기</Link>
                    </section>
                )}
            </div>
        </div>
    );
}
