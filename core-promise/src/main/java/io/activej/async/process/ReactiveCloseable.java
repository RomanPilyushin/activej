/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.async.process;

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.recycle.Recyclers;
import io.activej.reactor.Reactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

import static io.activej.reactor.Reactor.getCurrentReactor;

/**
 * Describes methods that are used to handle exceptional behaviour or to handle closing.
 * <p>
 * After {@link #close()}, or {@link #closeEx(Exception)} is called, the following things
 * should be done:
 * <ul>
 * <li>Resources held by an object should be freed</li>
 * <li>All pending asynchronous operations should be completed exceptionally</li>
 * </ul>
 * All operations of this interface are idempotent.
 */
public interface ReactiveCloseable extends Reactive {
	Object STATIC = new Object() {{
		Recyclers.register(ReactiveCloseable.class, ReactiveCloseable::close);
	}};

	/**
	 * Cancels the process.
	 */
	default void close() {
		closeEx(new AsyncCloseException());
	}

	/**
	 * Closes process exceptionally in case an exception
	 * is thrown while executing the given process.
	 *
	 * @param e exception that is used to close process with
	 */
	void closeEx(@NotNull Exception e);

	static ReactiveCloseable of(Consumer<Exception> exceptionConsumer) {
		return new ReactiveCloseable() {
			private final Reactor reactor = getCurrentReactor();

			@Override
			public void closeEx(@NotNull Exception e) {
				exceptionConsumer.accept(e);
			}

			@Override
			public Reactor getReactor() {
				return reactor;
			}
		};
	}
}