import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ClipboardList, Pencil } from 'lucide-react';
import VideoUploader from '../components/VideoUploader';
import {
    createMission, getMission, getTeam, getTeamMembers, getVideoUrl, updateMission, uploadVideo,
} from '../api/client';
import { isNoAccess, teamErrorMessage } from './teamErrors';
import './Teams.css';
import './Missions.css';

const MAX_TITLE = 50;   // 서버(MissionService)와 같은 값
const MAX_TEXT = 1000;

/**
 * 미션 만들기(/teams/:teamId/missions/new)와 수정(/teams/:teamId/missions/:missionId/edit). 팀장만.
 *
 * 만들기: 제목(필수), 안내 텍스트(선택), 레퍼런스 영상(필수), 대상자(활동 중 팀원 1명 이상, 팀장 제외).
 * 수정: 제목·안내 텍스트·레퍼런스 영상만 — 대상자는 만든 뒤 바꿀 수 없다.
 * 영상은 Mission 용도로 먼저 올리고(분석하지 않음), 그 id로 미션을 만든다.
 */
export default function MissionEditor() {
    const { teamId, missionId } = useParams();
    const editing = Boolean(missionId);
    const navigate = useNavigate();

    const [loaded, setLoaded] = useState(false);
    const [title, setTitle] = useState('');
    const [description, setDescription] = useState('');
    const [currentRefId, setCurrentRefId] = useState(null);
    const [file, setFile] = useState(null);
    const [candidates, setCandidates] = useState([]);
    const [selected, setSelected] = useState([]);
    const [error, setError] = useState('');
    const [progress, setProgress] = useState(null);

    useEffect(() => {
        let cancelled = false;
        const backToTeam = (notice) => navigate(`/teams/${teamId}?tab=missions`, { replace: true, state: { notice } });
        (async () => {
            try {
                if (editing) {
                    const m = await getMission(missionId);
                    if (cancelled) return;
                    if (m.myRole !== 'LEADER') return backToTeam('팀장만 미션을 수정할 수 있어요.');
                    setTitle(m.title);
                    setDescription(m.description ?? '');
                    setCurrentRefId(m.referenceVideoId);
                } else {
                    const [team, members] = await Promise.all([getTeam(teamId), getTeamMembers(teamId)]);
                    if (cancelled) return;
                    if (team.myRole !== 'LEADER') return backToTeam('팀장만 미션을 만들 수 있어요.');
                    // 대상자 후보: 활동 중 팀원(팀장 제외). 나가거나 추방된 사람은 목록에 없다.
                    setCandidates(members.filter((m) => m.role === 'MEMBER'));
                }
                setLoaded(true);
            } catch (err) {
                if (cancelled) return;
                if (isNoAccess(err)) {
                    navigate('/teams', { replace: true, state: { notice: teamErrorMessage(err) } });
                } else if (err.code === 'MISSION_DELETED' || err.code === 'MISSION_NOT_FOUND') {
                    backToTeam(teamErrorMessage(err));
                } else {
                    setError(teamErrorMessage(err, '정보를 불러오지 못했어요.'));
                }
            }
        })();
        return () => { cancelled = true; };
    }, [editing, missionId, teamId, navigate]);

    const toggle = (userId) => {
        setSelected((prev) => prev.includes(userId) ? prev.filter((id) => id !== userId) : [...prev, userId]);
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!title.trim()) return setError('제목을 입력해주세요.');
        if (!editing && !file) return setError('레퍼런스 영상을 올려주세요.');
        if (!editing && selected.length === 0) return setError('대상자를 1명 이상 선택해주세요.');

        setError('');
        setProgress(0);
        try {
            let referenceVideoId = currentRefId;
            if (file) {
                const uploaded = await uploadVideo(file, 'REFERENCE', {}, setProgress, 'MISSION');
                referenceVideoId = uploaded.videoId;
            }
            const body = { title: title.trim(), description: description.trim() || null, referenceVideoId };
            if (editing) {
                await updateMission(missionId, body);
                navigate(`/teams/${teamId}/missions/${missionId}`, { replace: true });
            } else {
                const created = await createMission(teamId, { ...body, targetUserIds: selected });
                navigate(`/teams/${teamId}/missions/${created.missionId}`, { replace: true });
            }
        } catch (err) {
            setError(teamErrorMessage(err, editing ? '미션을 수정하지 못했어요.' : '미션을 만들지 못했어요.'));
            setProgress(null);
        }
    };

    const busy = progress !== null;
    const cancelTo = editing ? `/teams/${teamId}/missions/${missionId}` : `/teams/${teamId}?tab=missions`;

    return (
        <div className="page">
            <div className="container team-form-container">
                <div className="page-header">
                    <h1 className="heading-icon">
                        {editing ? <><Pencil size={24} /> 미션 수정</> : <><ClipboardList size={26} /> 새 미션</>}
                    </h1>
                    <p>{editing ? '제목·안내 텍스트·레퍼런스 영상을 바꿀 수 있어요. 대상자는 바꿀 수 없어요'
                        : '팀원에게 따라 출 영상을 보내고 제출을 받아요'}</p>
                </div>

                {!loaded ? (
                    error ? <div className="auth-error">{error}</div>
                        : <div className="loading"><div className="spinner"></div></div>
                ) : (
                    <form className="card team-form" onSubmit={handleSubmit}>
                        {error && <div className="auth-error">{error}</div>}

                        <div className="form-group">
                            <label className="form-label" htmlFor="mission-title">제목</label>
                            <input id="mission-title" type="text" className="form-input" value={title}
                                maxLength={MAX_TITLE} onChange={(e) => setTitle(e.target.value)}
                                placeholder="예: 후렴 8카운트 맞춰 오기" autoFocus />
                            <span className="team-form-hint">{title.trim().length} / {MAX_TITLE}</span>
                        </div>

                        <div className="form-group">
                            <label className="form-label" htmlFor="mission-desc">안내 텍스트 (선택)</label>
                            <textarea id="mission-desc" className="form-textarea" rows={4} value={description}
                                maxLength={MAX_TEXT} onChange={(e) => setDescription(e.target.value)}
                                placeholder="연습할 구간, 신경 쓸 동작 등" />
                        </div>

                        <div className="form-group">
                            <span className="form-label">레퍼런스 영상{editing ? '' : ' (필수)'}</span>
                            {editing && currentRefId && !file && (
                                <div className="video-container mission-video mb-2">
                                    <video src={getVideoUrl(currentRefId)} controls playsInline preload="metadata" />
                                </div>
                            )}
                            <VideoUploader
                                label={editing ? '새 영상으로 바꾸기 (선택)' : '따라 출 영상을 올려주세요'}
                                onSelect={setFile}
                                selectedFile={file}
                            />
                        </div>

                        {!editing && (
                            <fieldset className="form-group mission-targets">
                                <legend className="form-label">대상자 (1명 이상)</legend>
                                {candidates.length === 0 ? (
                                    <p className="hint-text">
                                        아직 팀원이 없어요. 멤버 탭의 참여 코드로 팀원을 먼저 초대해주세요.
                                    </p>
                                ) : candidates.map((m) => (
                                    <label key={m.userId} className="mission-target-option">
                                        <input type="checkbox" checked={selected.includes(m.userId)}
                                            onChange={() => toggle(m.userId)} />
                                        <span>{m.nickname}</span>
                                    </label>
                                ))}
                            </fieldset>
                        )}

                        {busy && (
                            <div className="mission-progress" role="progressbar" aria-valuenow={Math.round(progress * 100)}
                                aria-valuemin={0} aria-valuemax={100}>
                                <div className="mission-progress-bar" style={{ width: `${Math.round(progress * 100)}%` }} />
                                <span>{progress < 1 ? `영상 올리는 중 ${Math.round(progress * 100)}%` : '저장하는 중…'}</span>
                            </div>
                        )}

                        <div className="team-form-buttons">
                            <Link to={cancelTo} className="btn btn-secondary">취소</Link>
                            <button type="submit" className="btn btn-primary" disabled={busy}>
                                {editing ? '저장' : '만들기'}
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}
