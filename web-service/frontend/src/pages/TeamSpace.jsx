import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ClipboardList, Copy, Crown, MessageCircle, Users } from 'lucide-react';
import { getJoinCode, getTeam, getTeamMembers, kickMember, leaveTeam } from '../api/client';
import { RoleBadge } from './Teams';
import { isNoAccess, teamErrorMessage } from './teamErrors';
import './Teams.css';

const TABS = [
    { key: 'chat', label: '채팅' },
    { key: 'missions', label: '미션' },
    { key: 'members', label: '멤버' },
];

/**
 * Team Space — 상단 한 줄(팀 이름 · 내 역할 / 나가기) + [채팅][미션][멤버] 탭.
 *
 * 활동 중 멤버가 아니면 서버가 403/404를 주고, 그때는 TEAM 탭으로 돌려보낸다.
 * 화면에 머무는 동안 추방되는 경우도 다음 요청에서 같은 방식으로 나가게 된다
 * (실시간 갱신은 이번 범위가 아니다).
 */
export default function TeamSpace() {
    const { teamId } = useParams();
    const navigate = useNavigate();
    const [team, setTeam] = useState(null);
    const [tab, setTab] = useState('chat');
    const [error, setError] = useState('');
    const [confirmLeave, setConfirmLeave] = useState(false);
    const [leaving, setLeaving] = useState(false);

    const leaveSpace = useCallback((err) => {
        navigate('/teams', { replace: true, state: { notice: teamErrorMessage(err) } });
    }, [navigate]);

    const [reloadKey, setReloadKey] = useState(0);
    const reloadTeam = useCallback(() => setReloadKey((k) => k + 1), []);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const data = await getTeam(teamId);
                if (!cancelled) setTeam(data);
            } catch (err) {
                if (cancelled) return;
                if (isNoAccess(err)) {
                    leaveSpace(err);
                } else {
                    setError(teamErrorMessage(err, '팀 정보를 불러오지 못했어요.'));
                }
            }
        })();
        return () => { cancelled = true; };
    }, [teamId, leaveSpace, reloadKey]);

    const handleLeave = async () => {
        setLeaving(true);
        setError('');
        try {
            await leaveTeam(teamId);
            // TODO(chat): 팀 채팅방 구독 해제 (서버 쪽 연결 지점은 TeamService.leave)
            navigate('/teams', { replace: true });
        } catch (err) {
            if (isNoAccess(err)) {
                leaveSpace(err);
                return;
            }
            setError(teamErrorMessage(err, '팀을 나가지 못했어요.'));
            setLeaving(false);
            setConfirmLeave(false);
        }
    };

    if (!team) {
        return (
            <div className="page">
                <div className="container team-container">
                    {error ? <div className="auth-error">{error}</div>
                        : <div className="loading"><div className="spinner"></div></div>}
                </div>
            </div>
        );
    }

    const isLeader = team.myRole === 'LEADER';

    return (
        <div className="page">
            <div className="container team-container">
                {/* 상단 한 줄: 왼쪽 팀 이름 · 내 역할, 오른쪽 끝 [나가기](팀원만) */}
                <div className="team-space-top">
                    <div className="team-space-title">
                        <h1>{team.name}</h1>
                        <RoleBadge role={team.myRole} />
                    </div>
                    {!isLeader && (
                        <div className="team-space-leave">
                            {confirmLeave ? (
                                <>
                                    <span className="team-confirm-text">정말 나갈까요?</span>
                                    <button type="button" className="btn btn-primary btn-sm"
                                        onClick={handleLeave} disabled={leaving}>
                                        {leaving ? '나가는 중...' : '나가기'}
                                    </button>
                                    <button type="button" className="btn btn-secondary btn-sm"
                                        onClick={() => setConfirmLeave(false)} disabled={leaving}>
                                        취소
                                    </button>
                                </>
                            ) : (
                                <button type="button" className="btn btn-outline btn-sm"
                                    onClick={() => setConfirmLeave(true)}>
                                    나가기
                                </button>
                            )}
                        </div>
                    )}
                </div>

                {error && <div className="auth-error">{error}</div>}

                <div className="team-tabs" role="tablist">
                    {TABS.map((t) => (
                        <button
                            key={t.key}
                            type="button"
                            role="tab"
                            aria-selected={tab === t.key}
                            className={`team-tab${tab === t.key ? ' active' : ''}`}
                            onClick={() => setTab(t.key)}
                        >
                            {t.label}
                        </button>
                    ))}
                </div>

                <div className="team-tab-panel" role="tabpanel">
                    {tab === 'chat' && (
                        // TODO(chat): 팀 채팅 화면 연결
                        <div className="empty-state">
                            <span className="empty-state-icon"><MessageCircle size={48} /></span>
                            <h3 className="empty-state-title">채팅은 준비 중입니다</h3>
                        </div>
                    )}
                    {tab === 'missions' && (
                        // 미션 목록·[새 미션]은 다음 작업(feature/mission)에서 채운다.
                        <div className="empty-state">
                            <span className="empty-state-icon"><ClipboardList size={48} /></span>
                            <h3 className="empty-state-title">미션은 준비 중입니다</h3>
                        </div>
                    )}
                    {tab === 'members' && (
                        <MembersTab
                            teamId={teamId}
                            isLeader={isLeader}
                            onNoAccess={leaveSpace}
                            onChanged={reloadTeam}
                        />
                    )}
                </div>
            </div>
        </div>
    );
}

/**
 * 멤버 탭 — (팀장만) 참여 코드 + 복사, 그 아래 활동 중 멤버 목록.
 * 참여 코드는 이 탭에만 둔다(탭 사이 중복 금지).
 */
function MembersTab({ teamId, isLeader, onNoAccess, onChanged }) {
    const [members, setMembers] = useState(null);
    const [joinCode, setJoinCode] = useState('');
    const [copied, setCopied] = useState(false);
    const [error, setError] = useState('');
    const [confirmKickId, setConfirmKickId] = useState(null);
    const [kicking, setKicking] = useState(false);

    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const [list, code] = await Promise.all([
                    getTeamMembers(teamId),
                    isLeader ? getJoinCode(teamId) : Promise.resolve(null),
                ]);
                if (cancelled) return;
                setMembers(list);
                if (code) setJoinCode(code.joinCode);
            } catch (err) {
                if (cancelled) return;
                if (isNoAccess(err)) {
                    onNoAccess(err);
                } else {
                    setError(teamErrorMessage(err, '멤버를 불러오지 못했어요.'));
                }
            }
        })();
        return () => { cancelled = true; };
    }, [teamId, isLeader, onNoAccess, reloadKey]);

    useEffect(() => {
        if (!copied) return undefined;
        const timer = setTimeout(() => setCopied(false), 2000);
        return () => clearTimeout(timer);
    }, [copied]);

    const handleCopy = async () => {
        try {
            await navigator.clipboard.writeText(joinCode);
            setCopied(true);
        } catch {
            // 클립보드를 쓸 수 없는 환경(권한 거부 등) — 코드는 화면에서 직접 선택할 수 있다.
            setError('복사하지 못했어요. 코드를 길게 눌러 직접 복사해주세요.');
        }
    };

    const handleKick = async (userId) => {
        setKicking(true);
        setError('');
        try {
            await kickMember(teamId, userId);
            setConfirmKickId(null);
            setReloadKey((k) => k + 1);
            onChanged();
        } catch (err) {
            if (isNoAccess(err)) {
                onNoAccess(err);
                return;
            }
            setError(teamErrorMessage(err, '추방하지 못했어요.'));
            setConfirmKickId(null);
            setReloadKey((k) => k + 1);
        } finally {
            setKicking(false);
        }
    };

    if (!members) {
        return error ? <div className="auth-error">{error}</div>
            : <div className="loading"><div className="spinner"></div></div>;
    }

    return (
        <div className="team-members">
            {isLeader && joinCode && (
                <div className="team-code-box">
                    <div>
                        <span className="team-code-label">참여 코드</span>
                        <span className="team-code-value">{joinCode}</span>
                    </div>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={handleCopy}>
                        <Copy size={15} /> {copied ? '복사됨' : '복사'}
                    </button>
                </div>
            )}

            {error && <div className="auth-error">{error}</div>}

            <p className="team-members-count icon-inline"><Users size={15} /> 활동 중 {members.length}명</p>
            <ul className="team-member-list">
                {members.map((m) => (
                    <li key={m.userId} className="team-member-row">
                        <span className="team-member-name">
                            {m.nickname}
                            {m.role === 'LEADER' && (
                                <span className="team-member-leader icon-inline"><Crown size={13} /> 팀장</span>
                            )}
                        </span>
                        <span className="team-member-actions">
                            {/* TODO(chat): DM 화면 연결 */}
                            <button type="button" className="btn btn-secondary btn-sm" disabled
                                title="DM은 준비 중입니다">
                                DM
                            </button>
                            {isLeader && m.role !== 'LEADER' && (
                                confirmKickId === m.userId ? (
                                    <>
                                        <span className="team-confirm-text">추방할까요?</span>
                                        <button type="button" className="btn btn-primary btn-sm"
                                            onClick={() => handleKick(m.userId)} disabled={kicking}>
                                            {kicking ? '추방 중...' : '추방'}
                                        </button>
                                        <button type="button" className="btn btn-secondary btn-sm"
                                            onClick={() => setConfirmKickId(null)} disabled={kicking}>
                                            취소
                                        </button>
                                    </>
                                ) : (
                                    <button type="button" className="btn btn-outline btn-sm"
                                        onClick={() => setConfirmKickId(m.userId)}>
                                        추방
                                    </button>
                                )
                            )}
                        </span>
                    </li>
                ))}
            </ul>
        </div>
    );
}
