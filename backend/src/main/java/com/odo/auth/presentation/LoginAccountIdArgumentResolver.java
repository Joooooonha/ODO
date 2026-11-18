package com.odo.auth.presentation;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link LoginAccountId} 인자를 채운다. 접근 토큰의 sub가 계정 ID다.
 */
public class LoginAccountIdArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(LoginAccountId.class)
				&& (parameter.getParameterType() == long.class || parameter.getParameterType() == Long.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			return Long.parseLong(jwt.getSubject());
		}
		// permitAll 경로에 잘못 쓴 경우다. 보안 설정이 맞으면 일어나지 않는다
		throw new IllegalStateException("접근 토큰으로 인증되지 않은 요청이다");
	}

}
