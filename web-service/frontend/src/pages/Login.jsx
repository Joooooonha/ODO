import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { login, setCurrentUser, socialLoginUrl } from '../api/client';
import { DEV_LOGIN } from '../devLogin';
import './Auth.css';

export default function Login() {
    const navigate = useNavigate();
    const [formData, setFormData] = useState({
        loginId: '',
        password: ''
    });
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    const handleChange = (e) => {
        setFormData({
            ...formData,
            [e.target.name]: e.target.value
        });
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setLoading(true);

        try {
            const user = await login(formData.loginId, formData.password);
            setCurrentUser(user);
            navigate('/');
        } catch (err) {
            setError(err.message || '로그인에 실패했습니다.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="auth-page">
            <div className="auth-container">
                <div className="auth-header">
                    <h1>ODO</h1>
                    <p>춤 동작 분석 서비스에 오신 것을 환영합니다</p>
                </div>

                {/* 소셜 로그인이 기본 경로다. 아래 아이디/비밀번호는 개발 환경
                    전용이며, 운영에서는 서버가 해당 API를 아예 등록하지 않는다. */}
                <div className="social-login">
                    <a className="social-btn social-kakao" href={socialLoginUrl('kakao')}>
                        카카오로 시작하기
                    </a>
                    <a className="social-btn social-naver" href={socialLoginUrl('naver')}>
                        네이버로 시작하기
                    </a>
                </div>

                {/* 아이디/비밀번호 로그인은 개발 환경 전용이다. 운영 빌드에서는
                    백엔드가 해당 엔드포인트를 아예 등록하지 않아(404) 눌러도
                    실패한다. 되지 않는 입력칸을 보여주는 것 자체가 혼란이므로
                    빌드 시점에 제거한다. */}
                {DEV_LOGIN && <>
                <div className="auth-divider"><span>또는 (개발용)</span></div>

                <form className="auth-form" onSubmit={handleSubmit}>
                    {error && <div className="auth-error">{error}</div>}

                    <div className="form-group">
                        <label className="form-label">아이디</label>
                        <input
                            type="text"
                            name="loginId"
                            className="form-input"
                            placeholder="아이디를 입력하세요"
                            value={formData.loginId}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <div className="form-group">
                        <label className="form-label">비밀번호</label>
                        <input
                            type="password"
                            name="password"
                            className="form-input"
                            placeholder="비밀번호를 입력하세요"
                            value={formData.password}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <button
                        type="submit"
                        className="btn btn-primary btn-lg auth-submit"
                        disabled={loading}
                    >
                        {loading ? '로그인 중...' : '로그인'}
                    </button>
                </form>
                </>}

                {/* 회원가입은 개발 전용이다. 운영에는 회원가입 API가 없어 눌러도 실패한다. */}
                {DEV_LOGIN && (
                    <div className="auth-footer">
                        <p>
                            아직 계정이 없으신가요?{' '}
                            <Link to="/signup">회원가입</Link>
                        </p>
                    </div>
                )}
            </div>
        </div>
    );
}
