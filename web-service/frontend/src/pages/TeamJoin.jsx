import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Crown, Ticket, Users } from 'lucide-react';
import { joinTeam, previewJoin } from '../api/client';
import { teamErrorMessage } from './teamErrors';
import './Teams.css';

/**
 * 팀 참여 — 코드 입력 → 확인("OOO 팀에 참여할까요?") → 참여 → Team Space.
 *
 * 확인 단계를 두는 이유는 다른 팀에 잘못 들어가는 것을 한 번 더 막기 위해서다.
 * 코드는 서버가 대소문자·공백·하이픈을 정리하므로 입력 그대로 보낸다.
 */
export default function TeamJoin() {
    const navigate = useNavigate();
    const [code, setCode] = useState('');
    const [preview, setPreview] = useState(null);
    const [error, setError] = useState('');
    const [alreadyMember, setAlreadyMember] = useState(false);
    const [busy, setBusy] = useState(false);

    const handlePreview = async (e) => {
        e.preventDefault();
        if (!code.trim()) {
            setError('참여 코드를 입력해주세요.');
            return;
        }
        setError('');
        setBusy(true);
        try {
            setPreview(await previewJoin(code));
        } catch (err) {
            setError(teamErrorMessage(err, '코드를 확인하지 못했어요.'));
        } finally {
            setBusy(false);
        }
    };

    const handleJoin = async () => {
        setError('');
        setAlreadyMember(false);
        setBusy(true);
        try {
            const team = await joinTeam(code);
            navigate(`/teams/${team.teamId}`, { replace: true });
        } catch (err) {
            setError(teamErrorMessage(err, '팀에 참여하지 못했어요.'));
            setAlreadyMember(err.code === 'ALREADY_MEMBER');
            setBusy(false);
        }
    };

    const backToInput = () => {
        setPreview(null);
        setError('');
        setAlreadyMember(false);
    };

    return (
        <div className="page">
            <div className="container team-form-container">
                <div className="page-header">
                    <h1 className="heading-icon"><Ticket size={26} /> 팀 참여</h1>
                    <p>팀장에게 받은 8자리 참여 코드를 입력하세요</p>
                </div>

                {!preview ? (
                    <form className="card team-form" onSubmit={handlePreview}>
                        {error && <div className="auth-error">{error}</div>}
                        <div className="form-group">
                            <label className="form-label" htmlFor="join-code">참여 코드</label>
                            <input
                                id="join-code"
                                type="text"
                                className="form-input team-code-input"
                                placeholder="예: AB3D-7KPQ"
                                value={code}
                                maxLength={12}
                                autoComplete="off"
                                autoCapitalize="characters"
                                spellCheck={false}
                                onChange={(e) => setCode(e.target.value)}
                                autoFocus
                            />
                        </div>
                        <div className="team-form-buttons">
                            <Link to="/teams" className="btn btn-secondary">취소</Link>
                            <button type="submit" className="btn btn-primary" disabled={busy}>
                                {busy ? '확인 중...' : '다음'}
                            </button>
                        </div>
                    </form>
                ) : (
                    <div className="card team-form">
                        {error && (
                            <div className="auth-error">
                                {error}
                                {alreadyMember && (
                                    <> <Link to="/teams" className="team-inline-link">팀 목록으로</Link></>
                                )}
                            </div>
                        )}
                        <h2 className="team-confirm-title">
                            <span className="team-confirm-name">{preview.name}</span> 팀에 참여할까요?
                        </h2>
                        <div className="team-confirm-meta">
                            <span className="icon-inline"><Crown size={14} /> 팀장 {preview.leaderNickname ?? '-'}</span>
                            <span className="icon-inline"><Users size={14} /> {preview.memberCount}명</span>
                        </div>
                        <div className="team-form-buttons">
                            <button type="button" className="btn btn-secondary" onClick={backToInput} disabled={busy}>
                                다른 코드 입력
                            </button>
                            <button type="button" className="btn btn-primary" onClick={handleJoin} disabled={busy}>
                                {busy ? '참여 중...' : '참여'}
                            </button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
