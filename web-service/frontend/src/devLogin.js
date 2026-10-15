/**
 * 개발용 로그인·회원가입 노출 여부.
 *
 * 값은 `frontend/.env.development`에만 있어서 `npm run dev`에서만 true다.
 * 배포 빌드(`npm run build`, deploy.yml)에는 값이 없어 false가 된다.
 * 백엔드도 `dev` 프로파일에서만 로그인·회원가입 엔드포인트를 등록한다 —
 * 운영에서 이 화면들을 보여주면 눌러도 실패할 뿐이다.
 *
 * 로그인 폼, 로그인 화면의 회원가입 링크, 헤더의 회원가입 버튼, `/signup` 라우트가
 * 모두 이 값 하나를 본다.
 */
export const DEV_LOGIN = import.meta.env.VITE_ENABLE_DEV_LOGIN === 'true';
