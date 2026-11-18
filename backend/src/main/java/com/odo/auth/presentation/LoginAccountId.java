package com.odo.auth.presentation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 인자에 접근 토큰의 계정 ID(sub)를 넣는다. 인자 타입은 long.
 * 인증이 필요한 API에서만 쓴다. 인증되지 않은 요청은 보안 필터가 먼저 401로 막는다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginAccountId {

}
