package com.odo.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 같은 요청을 여러 스레드에서 한꺼번에 출발시키고, 응답을 모아 돌려준다. 동시성 테스트의 준비용이다.
 */
public final class Concurrently {

	private Concurrently() {
	}

	public static List<MockHttpServletResponse> run(int threads, Callable<ResultActions> action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return action.call().andReturn().getResponse();
				}));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get());
			}
			return responses;
		}
		finally {
			executor.shutdownNow();
		}
	}

	public static List<Integer> statuses(List<MockHttpServletResponse> responses) {
		return responses.stream().map(MockHttpServletResponse::getStatus).toList();
	}

}
