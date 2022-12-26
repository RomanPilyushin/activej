package io.activej.reactor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

final class ThreadLocalReactor {
	private static final ThreadLocal<Reactor> CURRENT_REACTOR = new ThreadLocal<>();

	private static final String NO_CURRENT_REACTOR_ERROR =
//			"""
//					Trying to start async operations prior eventloop.run(), or from outside of eventloop.run()\s
//					Possible solutions: 1) Eventloop.create().withCurrentThread() ... {your code block} ... eventloop.run()\s
//					2) try_with_resources Eventloop.useCurrentThread() ... {your code block}\s
//					3) refactor application so it starts async operations within eventloop.run(),\s
//					   i.e. by implementing EventloopService::start() {your code block} and using ServiceGraphModule""";
			"No reactor in current thread";

	static @NotNull Reactor getCurrentReactor() {
		Reactor reactor = ThreadLocalReactor.CURRENT_REACTOR.get();
		if (reactor != null) return reactor;
		throw new IllegalStateException(NO_CURRENT_REACTOR_ERROR);
	}

	static @Nullable Reactor getCurrentReactorOrNull() {
		return CURRENT_REACTOR.get();
	}

	static void setCurrentReactor(@NotNull Reactor reactor) {
		CURRENT_REACTOR.set(reactor);
	}

	static void initWithReactor(@NotNull Reactor anotherReactor, @NotNull Runnable runnable) {
		Reactor reactor = CURRENT_REACTOR.get();
		try {
			CURRENT_REACTOR.set(anotherReactor);
			runnable.run();
		} finally {
			CURRENT_REACTOR.set(reactor);
		}
	}

	static <T> T initWithReactor(@NotNull Reactor anotherReactor, @NotNull Supplier<T> callable) {
		Reactor reactor = CURRENT_REACTOR.get();
		try {
			CURRENT_REACTOR.set(anotherReactor);
			return callable.get();
		} finally {
			CURRENT_REACTOR.set(reactor);
		}
	}

}