import { createContext } from 'react';

/**
 * 채팅 저장소 컨텍스트. 컴포넌트가 아닌 값은 별도 파일에 둔다(Fast Refresh — auth/authContext.js와 같은 이유).
 */
export const ChatContext = createContext(null);
