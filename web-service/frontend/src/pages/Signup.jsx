import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { signup, setCurrentUser } from '../api/client';
import './Auth.css';

export default function Signup() {
    const navigate = useNavigate();
    const [formData, setFormData] = useState({
        loginId: '',
        password: '',
        passwordConfirm: '',
        nickname: ''
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

        // 비밀번호 확인
        if (formData.password !== formData.passwordConfirm) {
            setError('비밀번호가 일치하지 않습니다.');
            return;
        }

        setLoading(true);

        try {
            const requestData = {
                loginId: formData.loginId,
                password: formData.password,
                nickname: formData.nickname
            };

            const user = await signup(requestData);
            setCurrentUser(user);
            navigate('/');
        } catch (err) {
            setError(err.message || '회원가입에 실패했습니다.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="auth-page">
            <div className="auth-container">
                <div className="auth-header">
                    <span className="auth-icon"><Sparkles size={40} /></span>
                    <h1>회원가입</h1>
                    <p>ODO에 가입하고 춤 연습을 시작하세요</p>
                </div>

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
                        <label className="form-label">닉네임</label>
                        <input
                            type="text"
                            name="nickname"
                            className="form-input"
                            placeholder="닉네임을 입력하세요"
                            value={formData.nickname}
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

                    <div className="form-group">
                        <label className="form-label">비밀번호 확인</label>
                        <input
                            type="password"
                            name="passwordConfirm"
                            className="form-input"
                            placeholder="비밀번호를 다시 입력하세요"
                            value={formData.passwordConfirm}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <button
                        type="submit"
                        className="btn btn-primary btn-lg auth-submit"
                        disabled={loading}
                    >
                        {loading ? '가입 중...' : '가입하기'}
                    </button>
                </form>

                <div className="auth-footer">
                    <p>
                        이미 계정이 있으신가요?{' '}
                        <Link to="/login">로그인</Link>
                    </p>
                </div>
            </div>
        </div>
    );
}
