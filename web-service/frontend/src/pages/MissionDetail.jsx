import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronDown, ChevronUp, MessageSquare, Send, Upload } from 'lucide-react';
import VideoUploader from '../components/VideoUploader';
import {
    addComment, deleteMission, getMission, getSubmission, getVideoUrl, submitMission, uploadVideo,
} from '../api/client';
import { SubmitBadge } from './MissionsTab';
import { isNoAccess, teamErrorMessage } from './teamErrors';
import './Teams.css';
import './Missions.css';

const MAX_TEXT = 1000;   // 서버(MissionService)와 같은 값
const MAX_COMMENT = 500;

/**
 * 미션 상세 — 제목, 안내 텍스트, 레퍼런스 영상, 제출 현황.
 *
 * - 내가 대상자이고 미제출이면 제출 영역(영상 필수 + 텍스트 선택)
 * - 제출 완료 항목을 누르면 그 아래에 제출물(영상, 텍스트, 코멘트)이 펼쳐진다
 * - 팀장에게만 [수정]·[삭제](그 자리 2단계 확인)
 * - 분석 결과 화면은 없다 — Mission 영상은 분석하지 않는다
 * - 삭제된 미션이면 "삭제된 미션입니다"를 들고 Team Space 미션 탭으로 돌려보낸다
 */
export default function MissionDetail() {
    const { teamId, missionId } = useParams();
    const navigate = useNavigate();
    const [mission, setMission] = useState(null);
    const [error, setError] = useState('');
    const [openSubmission, setOpenSubmission] = useState(null);
    const [confirmDelete, setConfirmDelete] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    const missionsTab = `/teams/${teamId}?tab=missions`;

    // 이 화면에 더 머물 수 없는 오류면 알맞은 곳으로 돌려보낸다. 처리했으면 true.
    const redirectIfGone = useCallback((err) => {
        if (isNoAccess(err)) {
            navigate('/teams', { replace: true, state: { notice: teamErrorMessage(err) } });
            return true;
        }
        if (err.code === 'MISSION_DELETED' || err.code === 'MISSION_NOT_FOUND') {
            navigate(missionsTab, { replace: true, state: { notice: teamErrorMessage(err) } });
            return true;
        }
        return false;
    }, [navigate, missionsTab]);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const data = await getMission(missionId);
                if (!cancelled) setMission(data);
            } catch (err) {
                if (cancelled || redirectIfGone(err)) return;
                setError(teamErrorMessage(err, '미션을 불러오지 못했어요.'));
            }
        })();
        return () => { cancelled = true; };
    }, [missionId, reloadKey, redirectIfGone]);

    const handleDelete = async () => {
        setDeleting(true);
        try {
            await deleteMission(missionId);
            navigate(missionsTab, { replace: true });
        } catch (err) {
            if (redirectIfGone(err)) return;
            setError(teamErrorMessage(err, '미션을 삭제하지 못했어요.'));
            setDeleting(false);
            setConfirmDelete(false);
        }
    };

    if (!mission) {
        return (
            <div className="page">
                <div className="container team-container">
                    {error ? <div className="auth-error">{error}</div>
                        : <div className="loading"><div className="spinner"></div></div>}
                </div>
            </div>
        );
    }

    const isLeader = mission.myRole === 'LEADER';
    const submitted = mission.targets.filter((t) => t.submissionId).length;

    return (
        <div className="page">
            <div className="container team-container">
                <Link to={missionsTab} className="mission-back icon-inline"><ArrowLeft size={16} /> 미션 목록</Link>

                <div className="team-space-top">
                    <div className="team-space-title">
                        <h1>{mission.title}</h1>
                        {mission.myStatus && <SubmitBadge status={mission.myStatus} />}
                    </div>
                    {isLeader && (
                        <div className="team-space-leave">
                            {confirmDelete ? (
                                <>
                                    <span className="team-confirm-text">미션을 삭제할까요?</span>
                                    <button type="button" className="btn btn-primary btn-sm"
                                        onClick={handleDelete} disabled={deleting}>
                                        {deleting ? '삭제 중...' : '삭제'}
                                    </button>
                                    <button type="button" className="btn btn-secondary btn-sm"
                                        onClick={() => setConfirmDelete(false)} disabled={deleting}>
                                        취소
                                    </button>
                                </>
                            ) : (
                                <>
                                    <Link to={`/teams/${teamId}/missions/${missionId}/edit`}
                                        className="btn btn-secondary btn-sm">수정</Link>
                                    <button type="button" className="btn btn-outline btn-sm"
                                        onClick={() => setConfirmDelete(true)}>
                                        삭제
                                    </button>
                                </>
                            )}
                        </div>
                    )}
                </div>

                {error && <div className="auth-error">{error}</div>}

                {mission.description && <p className="mission-description">{mission.description}</p>}

                <section className="mission-section">
                    <h2 className="mission-section-title">레퍼런스 영상</h2>
                    <div className="video-container mission-video">
                        <video src={getVideoUrl(mission.referenceVideoId)} controls playsInline preload="metadata" />
                    </div>
                </section>

                {mission.myStatus === 'NOT_SUBMITTED' && (
                    <SubmitArea
                        missionId={missionId}
                        onSubmitted={() => setReloadKey((k) => k + 1)}
                        onGone={redirectIfGone}
                    />
                )}

                <section className="mission-section">
                    <h2 className="mission-section-title">
                        제출 현황 <span className="mission-section-count">{submitted}/{mission.targets.length}</span>
                    </h2>
                    <ul className="team-member-list mission-status-list">
                        {mission.targets.map((target) => {
                            const open = openSubmission === target.submissionId && target.submissionId != null;
                            return (
                                <li key={target.userId} className="mission-status-item">
                                    {target.submissionId ? (
                                        <button type="button" className="mission-status-row"
                                            aria-expanded={open}
                                            onClick={() => setOpenSubmission(open ? null : target.submissionId)}>
                                            <span className="team-member-name">{target.nickname}</span>
                                            <span className="mission-status-right">
                                                <SubmitBadge status="SUBMITTED" />
                                                {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                                            </span>
                                        </button>
                                    ) : (
                                        <div className="mission-status-row">
                                            <span className="team-member-name">{target.nickname}</span>
                                            <SubmitBadge status="NOT_SUBMITTED" />
                                        </div>
                                    )}
                                    {open && <SubmissionPanel submissionId={target.submissionId} onGone={redirectIfGone} />}
                                </li>
                            );
                        })}
                    </ul>
                </section>
            </div>
        </div>
    );
}

/** 내가 대상자이고 미제출일 때만 보인다. 영상(필수) + 텍스트(선택). 제출은 한 번뿐이다. */
function SubmitArea({ missionId, onSubmitted, onGone }) {
    const [file, setFile] = useState(null);
    const [content, setContent] = useState('');
    const [progress, setProgress] = useState(null);
    const [error, setError] = useState('');

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!file) return setError('제출할 영상을 올려주세요.');
        setError('');
        setProgress(0);
        try {
            const uploaded = await uploadVideo(file, 'PRACTICE', {}, setProgress, 'MISSION');
            await submitMission(missionId, { videoId: uploaded.videoId, content: content.trim() || null });
            onSubmitted();
        } catch (err) {
            if (onGone(err)) return;
            setError(teamErrorMessage(err, '제출하지 못했어요.'));
            setProgress(null);
            if (err.code === 'ALREADY_SUBMITTED') onSubmitted();
        }
    };

    const busy = progress !== null;
    return (
        <form className="card team-form mission-submit" onSubmit={handleSubmit}>
            <h2 className="mission-section-title icon-inline"><Upload size={18} /> 제출하기</h2>
            <p className="hint-text">제출은 한 번만 할 수 있고, 제출한 뒤에는 바꿀 수 없어요.</p>
            {error && <div className="auth-error">{error}</div>}
            <VideoUploader label="연습 영상을 올려주세요" onSelect={setFile} selectedFile={file} />
            <div className="form-group mt-2">
                <label className="form-label" htmlFor="submit-content">남길 말 (선택)</label>
                <textarea id="submit-content" className="form-textarea" rows={3} value={content}
                    maxLength={MAX_TEXT} onChange={(e) => setContent(e.target.value)} />
            </div>
            {busy && (
                <div className="mission-progress" role="progressbar" aria-valuenow={Math.round(progress * 100)}
                    aria-valuemin={0} aria-valuemax={100}>
                    <div className="mission-progress-bar" style={{ width: `${Math.round(progress * 100)}%` }} />
                    <span>{progress < 1 ? `영상 올리는 중 ${Math.round(progress * 100)}%` : '제출하는 중…'}</span>
                </div>
            )}
            <div className="team-form-buttons">
                <button type="submit" className="btn btn-primary" disabled={busy}>제출</button>
            </div>
        </form>
    );
}

/** 제출물 보기 — 영상, 텍스트, 코멘트. 입력란은 팀장과 그 제출자에게만. 실시간 갱신은 없다. */
function SubmissionPanel({ submissionId, onGone }) {
    const [data, setData] = useState(null);
    const [error, setError] = useState('');
    const [comment, setComment] = useState('');
    const [sending, setSending] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const d = await getSubmission(submissionId);
                if (!cancelled) setData(d);
            } catch (err) {
                if (cancelled || onGone(err)) return;
                setError(teamErrorMessage(err, '제출물을 불러오지 못했어요.'));
            }
        })();
        return () => { cancelled = true; };
    }, [submissionId, reloadKey, onGone]);

    const handleComment = async (e) => {
        e.preventDefault();
        if (!comment.trim()) return;
        setSending(true);
        setError('');
        try {
            await addComment(submissionId, comment.trim());
            setComment('');
            setReloadKey((k) => k + 1);
        } catch (err) {
            if (onGone(err)) return;
            setError(teamErrorMessage(err, '코멘트를 남기지 못했어요.'));
        } finally {
            setSending(false);
        }
    };

    if (!data) {
        return (
            <div className="submission-panel">
                {error ? <div className="auth-error">{error}</div>
                    : <div className="loading"><div className="spinner"></div></div>}
            </div>
        );
    }

    return (
        <div className="submission-panel">
            <div className="video-container mission-video">
                <video src={getVideoUrl(data.videoId)} controls playsInline preload="metadata" />
            </div>
            <p className="submission-meta">
                {data.submitterNickname} · {new Date(data.createdAt).toLocaleString('ko-KR', {
                    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
                })}
            </p>
            {data.content && <p className="mission-description">{data.content}</p>}

            <h3 className="mission-section-title icon-inline"><MessageSquare size={16} /> 코멘트 {data.comments.length}</h3>
            {error && <div className="auth-error">{error}</div>}
            {data.comments.length === 0 ? (
                <p className="hint-text">아직 코멘트가 없어요.</p>
            ) : (
                <ul className="comment-list">
                    {data.comments.map((c) => (
                        <li key={c.commentId} className="comment-item">
                            <span className="comment-writer">{c.writerNickname}</span>
                            <p className="comment-content">{c.content}</p>
                        </li>
                    ))}
                </ul>
            )}
            {data.canComment && (
                <form className="comment-form" onSubmit={handleComment}>
                    <textarea className="form-textarea" rows={2} value={comment} maxLength={MAX_COMMENT}
                        onChange={(e) => setComment(e.target.value)} placeholder="코멘트를 남겨주세요"
                        aria-label="코멘트" />
                    <button type="submit" className="btn btn-primary btn-sm" disabled={sending || !comment.trim()}>
                        <Send size={15} /> 등록
                    </button>
                </form>
            )}
        </div>
    );
}
