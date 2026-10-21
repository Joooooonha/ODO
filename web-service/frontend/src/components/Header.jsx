import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { useAuth } from '../auth/useAuth';
import { DEV_LOGIN } from '../devLogin';
import './Header.css';

export default function Header() {
    const navigate = useNavigate();
    const { user, logout } = useAuth();
    const [menuOpen, setMenuOpen] = useState(false);
    const navRef = useRef(null);

    const handleLogout = async () => {
        await logout();
        navigate('/login');
    };

    const closeMenu = () => setMenuOpen(false);

    // 모바일 내비 드롭다운 — 바깥을 누르거나 Esc를 누르면 닫힌다.
    useEffect(() => {
        if (!menuOpen) return;

        const onKeyDown = (e) => { if (e.key === 'Escape') closeMenu(); };
        const onClickOutside = (e) => {
            if (navRef.current && !navRef.current.contains(e.target)) closeMenu();
        };

        document.addEventListener('keydown', onKeyDown);
        document.addEventListener('mousedown', onClickOutside);
        return () => {
            document.removeEventListener('keydown', onKeyDown);
            document.removeEventListener('mousedown', onClickOutside);
        };
    }, [menuOpen]);

    return (
        <header className="header">
            <div className="header-container">
                <Link to="/" className="logo" onClick={closeMenu}>
                    <span className="logo-text">ODO</span>
                </Link>

                {user && (
                    <nav ref={navRef} className={`nav${menuOpen ? ' open' : ''}`}>
                        <Link to="/" className="nav-link" onClick={closeMenu}>대시보드</Link>
                        <Link to="/practice" className="nav-link" onClick={closeMenu}>자유 연습</Link>
                        <Link to="/teams" className="nav-link" onClick={closeMenu}>팀</Link>
                        <Link to="/chats" className="nav-link" onClick={closeMenu}>채팅</Link>
                        <Link to="/logs" className="nav-link" onClick={closeMenu}>내 기록</Link>
                    </nav>
                )}

                <div className="header-actions">
                    {user ? (
                        <>
                            <div className="user-menu">
                                {/* 팀 정보는 TEAM 탭에서만 보여준다 — 여러 팀에 속하므로 "내 팀" 하나를 말할 수 없다. */}
                                <span className="user-info">
                                    <span className="user-name">{user.nickname}</span>
                                </span>
                                <button onClick={handleLogout} className="btn btn-outline btn-sm">
                                    로그아웃
                                </button>
                            </div>
                            <button
                                type="button"
                                className="nav-toggle"
                                onClick={() => setMenuOpen((v) => !v)}
                                aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
                                aria-expanded={menuOpen}
                            >
                                {menuOpen ? <X size={20} /> : <Menu size={20} />}
                            </button>
                        </>
                    ) : (
                        <div className="auth-buttons">
                            <Link to="/login" className="btn btn-secondary btn-sm">로그인</Link>
                            {DEV_LOGIN && (
                                <Link to="/signup" className="btn btn-primary btn-sm">회원가입</Link>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </header>
    );
}
