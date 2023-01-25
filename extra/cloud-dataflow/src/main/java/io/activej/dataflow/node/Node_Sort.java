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

package io.activej.dataflow.node;

import io.activej.dataflow.graph.StreamId;
import io.activej.dataflow.graph.StreamSchema;
import io.activej.dataflow.graph.Task;
import io.activej.dataflow.inject.SortingExecutor;
import io.activej.datastream.processor.IStreamSorterStorage;
import io.activej.datastream.processor.StreamSorter;
import io.activej.inject.Key;
import io.activej.promise.Promise;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Represents a node, which performs sorting of a data stream, based on key function and key comparator.
 *
 * @param <K> keys type
 * @param <T> data items type
 */
public final class Node_Sort<K, T> extends AbstractNode {

	public interface StreamSorterStorageFactory {
		<T> IStreamSorterStorage<T> create(StreamSchema<T> streamSchema, Task context, Promise<Void> taskExecuted);

		default <T> Promise<Void> cleanup(IStreamSorterStorage<T> storage) {
			return Promise.complete();
		}
	}

	private final StreamSchema<T> streamSchema;
	private final Function<T, K> keyFunction;
	private final Comparator<K> keyComparator;
	private final boolean deduplicate;
	private final int itemsInMemorySize;

	private final StreamId input;
	private final StreamId output;

	public Node_Sort(int index, StreamSchema<T> streamSchema, Function<T, K> keyFunction, Comparator<K> keyComparator,
			boolean deduplicate, int itemsInMemorySize, StreamId input) {
		this(index, streamSchema, keyFunction, keyComparator, deduplicate, itemsInMemorySize, input, new StreamId());
	}

	public Node_Sort(int index, StreamSchema<T> streamSchema, Function<T, K> keyFunction, Comparator<K> keyComparator,
			boolean deduplicate, int itemsInMemorySize, StreamId input, StreamId output) {
		super(index);
		this.streamSchema = streamSchema;
		this.keyFunction = keyFunction;
		this.keyComparator = keyComparator;
		this.deduplicate = deduplicate;
		this.itemsInMemorySize = itemsInMemorySize;
		this.input = input;
		this.output = output;
	}

	@Override
	public Collection<StreamId> getInputs() {
		return List.of(input);
	}

	@Override
	public Collection<StreamId> getOutputs() {
		return List.of(output);
	}

	@Override
	public void createAndBind(Task task) {
		Executor executor = task.get(Key.of(Executor.class, SortingExecutor.class));
		StreamSorterStorageFactory storageFactory = task.get(StreamSorterStorageFactory.class);
		IStreamSorterStorage<T> storage = storageFactory.create(streamSchema, task, task.getExecutionPromise());
		StreamSorter<K, T> streamSorter = StreamSorter.builder(storage, keyFunction, keyComparator, deduplicate, itemsInMemorySize)
				.withSortingExecutor(executor)
				.build();
		task.bindChannel(input, streamSorter.getInput());
		task.export(output, streamSorter.getOutput());
		streamSorter.getInput().getAcknowledgement()
				.whenComplete(() -> storageFactory.cleanup(storage));
	}

	public StreamSchema<T> getStreamSchema() {
		return streamSchema;
	}

	public Function<T, K> getKeyFunction() {
		return keyFunction;
	}

	public Comparator<K> getKeyComparator() {
		return keyComparator;
	}

	public boolean isDeduplicate() {
		return deduplicate;
	}

	public int getItemsInMemorySize() {
		return itemsInMemorySize;
	}

	public StreamId getInput() {
		return input;
	}

	public StreamId getOutput() {
		return output;
	}

	@Override
	public String toString() {
		return "NodeSort{type=" + streamSchema +
				", keyFunction=" + keyFunction.getClass().getSimpleName() +
				", keyComparator=" + keyComparator.getClass().getSimpleName() +
				", deduplicate=" + deduplicate +
				", itemsInMemorySize=" + itemsInMemorySize +
				", input=" + input +
				", output=" + output + '}';
	}
}
