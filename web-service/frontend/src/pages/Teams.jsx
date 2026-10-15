import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Crown, Plus, Ticket, Users } from 'lucide-react';
import { getMyTeams } from '../api/client';
import { teamErrorMessage } from './teamErrors';
import './Teams.css';

/** TEAM 탭 — 내가 활동 중인 팀만 보인다. */
export default function Teams() {
    const location = useLocation();
    const navigate = useNavigate();
    // Team Space에서 돌려보내질 때 넘어온 안내. 새로고침하면 다시 뜨지 않게 한 번만 읽는다.
    const [notice] = useState(() => location.state?.notice ?? '');
    const [teams, setTeams] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    useEffect(() => {
        if (location.state?.notice) {
            navigate(location.pathname, { replace: true, state: null });
        }
    }, [location, navigate]);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const data = await getMyTeams();
                if (!cancelled) setTeams(data);
            } catch (err) {
                if (!cancelled) setError(teamErrorMessage(err, '팀 목록을 불러오지 못했어요.'));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => { cancelled = true; };
    }, []);

    const actions = (
        <div className="team-actions">
            <Link to="/teams/new" className="btn btn-primary">
                <Plus size={18} /> 팀 만들기
            </Link>
            <Link to="/teams/join" className="btn btn-secondary">
                <Ticket size={18} /> 팀 참여
            </Link>
        </div>
    );

    return (
        <div className="teams-page page">
            <div className="container team-container">
                <div className="page-header">
                    <h1 className="heading-icon"><Users size={26} /> 팀</h1>
                    <p>함께 연습하는 팀을 만들거나, 받은 참여 코드로 팀에 들어가세요</p>
                </div>

                {notice && <div className="auth-error">{notice}</div>}
                {error && <div className="auth-error">{error}</div>}

                {loading ? (
                    <div className="loading"><div className="spinner"></div></div>
                ) : teams.length === 0 ? (
                    <div className="empty-state">
                        <span className="empty-state-icon"><Users size={48} /></span>
                        <h3 className="empty-state-title">아직 속한 팀이 없어요</h3>
                        <p className="empty-state-text">
                            팀을 직접 만들거나, 팀장에게 받은 참여 코드로 참여해보세요
                        </p>
                        <div className="team-actions team-actions-center mt-3">
                            <Link to="/teams/new" className="btn btn-primary">
                                <Plus size={18} /> 팀 만들기
                            </Link>
                            <Link to="/teams/join" className="btn btn-secondary">
                                <Ticket size={18} /> 팀 참여
                            </Link>
                        </div>
                    </div>
                ) : (
                    <>
                        {actions}
                        <div className="team-list">
                            {teams.map((team) => (
                                <Link key={team.teamId} to={`/teams/${team.teamId}`} className="team-card">
                                    <div className="team-card-info">
                                        <h3 className="team-card-name">{team.name}</h3>
                                        <div className="team-card-meta">
                                            <RoleBadge role={team.myRole} />
                                            <span className="icon-inline"><Users size={14} /> {team.memberCount}명</span>
                                        </div>
                                    </div>
                                    <span className="team-card-arrow">→</span>
                                </Link>
                            ))}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}

export function RoleBadge({ role }) {
    return role === 'LEADER' ? (
        <span className="badge badge-warning icon-inline"><Crown size={13} /> 팀장</span>
    ) : (
        <span className="badge badge-info">팀원</span>
    );
}
