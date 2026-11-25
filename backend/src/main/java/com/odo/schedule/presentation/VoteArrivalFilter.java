package com.odo.schedule.presentation;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 투표 요청의 서버 도착 시각을 찍는다. 03_Schedule_spec.md의 "접수 (도착부터 202까지)".
 * 인증 필터보다 먼저, 가장 먼저 실행되도록 등록한다(VoteArrivalFilterConfig). 앞에 남는 지연은 Tomcat이 스레드를 배정하기까지뿐이다.
 * 시각은 µs로 자른다. timestamptz가 µs까지 저장하므로 찍은 값과 저장된 값이 같아진다.
 * 투표 경로(POST /api/schedules/{id}/votes)에서만 찍는다. 서블릿의 URL 패턴으로는 가운데에 ID가 있는 경로를 고를 수 없어
 * 모든 요청에 등록하고 여기서 고른다.
 */
public class VoteArrivalFilter extends OncePerRequestFilter {

	private static final String ARRIVED_AT = VoteArrivalFilter.class.getName() + ".arrivedAt";

	private static final Pattern VOTE_PATH = Pattern.compile("^/api/schedules/[^/]+/votes$");

	private final Clock clock;

	public VoteArrivalFilter(Clock clock) {
		this.clock = clock;
	}

	/** 필터가 찍은 도착 시각. 투표 경로가 아니라 찍지 않았으면 IllegalStateException(설정 오류). */
	public static Instant arrivedAt(HttpServletRequest request) {
		Object arrivedAt = request.getAttribute(ARRIVED_AT);
		if (!(arrivedAt instanceof Instant instant)) {
			throw new IllegalStateException("투표 도착 시각이 없다. VoteArrivalFilter 등록을 확인한다");
		}
		return instant;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"POST".equals(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !VOTE_PATH.matcher(path).matches();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		request.setAttribute(ARRIVED_AT, clock.instant().truncatedTo(ChronoUnit.MICROS));
		chain.doFilter(request, response);
	}

}
