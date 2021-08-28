package io.activej.common.function;

import io.activej.common.exception.UncheckedException;

import java.util.function.BiConsumer;

@FunctionalInterface
public interface ThrowingBiConsumer<T, U> {
	void accept(T t, U u) throws Exception;

	static <T, U> ThrowingBiConsumer<T, U> of(BiConsumer<T, U> uncheckedFn) {
		return (t, u) -> {
			try {
				uncheckedFn.accept(t, u);
			} catch (UncheckedException e) {
				throw e.getCause();
			}
		};
	}

	static <T, U> BiConsumer<T, U> uncheckedOf(ThrowingBiConsumer<T, U> checkedFn) {
		return (t, u) -> {
			try {
				checkedFn.accept(t, u);
			} catch (Exception e) {
				throw UncheckedException.of(e);
			}
		};
	}
}
