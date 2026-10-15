import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { createTeam } from '../api/client';
import { teamErrorMessage } from './teamErrors';
import './Teams.css';

const MAX_NAME_LENGTH = 30; // 서버(TeamService)와 같은 값

/** 팀 만들기 — 만든 사람이 팀장이 되고, 만들자마자 그 팀의 Team Space로 간다. */
export default function TeamCreate() {
    const navigate = useNavigate();
    const [name, setName] = useState('');
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!name.trim()) {
            setError('팀 이름을 입력해주세요.');
            return;
        }
        setError('');
        setSubmitting(true);
        try {
            const team = await createTeam(name.trim());
            navigate(`/teams/${team.teamId}`, { replace: true });
        } catch (err) {
            setError(teamErrorMessage(err, '팀을 만들지 못했어요.'));
            setSubmitting(false);
        }
    };

    return (
        <div className="page">
            <div className="container team-form-container">
                <div className="page-header">
                    <h1 className="heading-icon"><Plus size={26} /> 팀 만들기</h1>
                    <p>팀을 만들면 내가 팀장이 되고, 팀원을 초대할 참여 코드가 생겨요</p>
                </div>

                <form className="card team-form" onSubmit={handleSubmit}>
                    {error && <div className="auth-error">{error}</div>}
                    <div className="form-group">
                        <label className="form-label" htmlFor="team-name">팀 이름</label>
                        <input
                            id="team-name"
                            type="text"
                            className="form-input"
                            placeholder="예: 화요일 저녁 크루"
                            value={name}
                            maxLength={MAX_NAME_LENGTH}
                            onChange={(e) => setName(e.target.value)}
                            autoFocus
                        />
                        <span className="team-form-hint">{name.trim().length} / {MAX_NAME_LENGTH}</span>
                    </div>
                    <div className="team-form-buttons">
                        <Link to="/teams" className="btn btn-secondary">취소</Link>
                        <button type="submit" className="btn btn-primary" disabled={submitting}>
                            {submitting ? '만드는 중...' : '만들기'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
