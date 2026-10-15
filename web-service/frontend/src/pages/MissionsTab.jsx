import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ClipboardList, Plus } from 'lucide-react';
import { getMissions } from '../api/client';
import { isNoAccess, teamErrorMessage } from './teamErrors';
import './Missions.css';

/**
 * Team Space의 [미션] 탭 — 최근 생성순 목록.
 *
 * 항목은 위에서 아래로 제목 → 제출 현황(3/5 제출) → 내가 대상자일 때만 내 상태 배지.
 * 대상자가 아니면 배지가 없다. 제출은 상세 화면에서만 한다(목록에 제출 버튼 없음, D16).
 * [새 미션]은 팀장에게만 탭 우측 상단에.
 */
export default function MissionsTab({ teamId, isLeader, onNoAccess }) {
    const [missions, setMissions] = useState(null);
    const [error, setError] = useState('');

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const data = await getMissions(teamId);
                if (!cancelled) setMissions(data);
            } catch (err) {
                if (cancelled) return;
                if (isNoAccess(err)) {
                    onNoAccess(err);
                } else {
                    setError(teamErrorMessage(err, '미션을 불러오지 못했어요.'));
                }
            }
        })();
        return () => { cancelled = true; };
    }, [teamId, onNoAccess]);

    return (
        <div className="missions-tab">
            {isLeader && (
                <div className="missions-tab-actions">
                    <Link to={`/teams/${teamId}/missions/new`} className="btn btn-primary btn-sm">
                        <Plus size={16} /> 새 미션
                    </Link>
                </div>
            )}

            {error && <div className="auth-error">{error}</div>}

            {!missions ? (
                !error && <div className="loading"><div className="spinner"></div></div>
            ) : missions.length === 0 ? (
                <div className="empty-state">
                    <span className="empty-state-icon"><ClipboardList size={48} /></span>
                    <h3 className="empty-state-title">아직 미션이 없어요</h3>
                    <p className="empty-state-text">
                        {isLeader ? '새 미션을 만들어 팀원에게 과제를 내보세요' : '팀장이 미션을 내면 여기에 보여요'}
                    </p>
                </div>
            ) : (
                <div className="mission-list">
                    {missions.map((m) => (
                        <Link key={m.missionId} to={`/teams/${teamId}/missions/${m.missionId}`} className="mission-card">
                            <span className="mission-card-title">{m.title}</span>
                            <span className="mission-card-progress">
                                {m.submittedCount}/{m.targetCount} 제출
                            </span>
                            {m.myStatus && <SubmitBadge status={m.myStatus} />}
                        </Link>
                    ))}
                </div>
            )}
        </div>
    );
}

/** 제출 상태는 두 가지뿐이다: 미제출 / 제출 완료. */
export function SubmitBadge({ status }) {
    return status === 'SUBMITTED'
        ? <span className="badge badge-success">제출 완료</span>
        : <span className="badge badge-warning">미제출</span>;
}
