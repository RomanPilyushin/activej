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

package io.activej.cube.service;

import io.activej.aggregation.Aggregation;
import io.activej.aggregation.AggregationChunk;
import io.activej.aggregation.AggregationChunkStorage;
import io.activej.aggregation.ChunkLocker;
import io.activej.aggregation.ot.AggregationDiff;
import io.activej.async.function.AsyncSupplier;
import io.activej.cube.Cube;
import io.activej.cube.exception.CubeException;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffScheme;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.jmx.EventloopJmxBeanWithStats;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.jmx.stats.ValueStats;
import io.activej.ot.OTStateManager;
import io.activej.promise.Promise;
import io.activej.promise.jmx.PromiseStats;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.activej.aggregation.util.Utils.wrapExceptionFn;
import static io.activej.async.function.AsyncSuppliers.reuse;
import static io.activej.async.util.LogUtils.thisMethod;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.transformMap;
import static io.activej.cube.service.ChunkLockerFactory.NOOP_CHUNK_LOCKER;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;

public final class CubeConsolidationController<K, D, C> implements EventloopJmxBeanWithStats {
	private static final Logger logger = LoggerFactory.getLogger(CubeConsolidationController.class);

	public static final Supplier<BiFunction<Aggregation, ChunkLocker<Object>, Promise<AggregationDiff>>> DEFAULT_LOCKER_STRATEGY = new Supplier<BiFunction<Aggregation,
			ChunkLocker<Object>,
			Promise<AggregationDiff>>>() {
		private boolean hotSegment = false;

		@Override
		public BiFunction<Aggregation, ChunkLocker<Object>, Promise<AggregationDiff>> get() {
			//noinspection AssignmentUsedAsCondition
			return (hotSegment = !hotSegment) ?
					Aggregation::consolidateHotSegment :
					Aggregation::consolidateMinKey;
		}
	};

	@Deprecated
	public static final Supplier<Function<Aggregation, Promise<AggregationDiff>>> DEFAULT_STRATEGY = () ->
			aggregation ->
					DEFAULT_LOCKER_STRATEGY.get().apply(aggregation, NOOP_CHUNK_LOCKER);

	public static final Duration DEFAULT_SMOOTHING_WINDOW = Duration.ofMinutes(5);

	private final Eventloop eventloop;
	private final CubeDiffScheme<D> cubeDiffScheme;
	private final Cube cube;
	private final OTStateManager<K, D> stateManager;
	private final AggregationChunkStorage<C> aggregationChunkStorage;

	private final Map<Aggregation, String> aggregationsMapReversed;

	private final PromiseStats promiseConsolidate = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseConsolidateImpl = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseCleanupIrrelevantChunks = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);

	private final ValueStats removedChunks = ValueStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final ValueStats removedChunksRecords = ValueStats.create(DEFAULT_SMOOTHING_WINDOW).withRate();
	private final ValueStats addedChunks = ValueStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final ValueStats addedChunksRecords = ValueStats.create(DEFAULT_SMOOTHING_WINDOW).withRate();

	private Supplier<BiFunction<Aggregation, ChunkLocker<Object>, Promise<AggregationDiff>>> strategy = DEFAULT_LOCKER_STRATEGY;
	private ChunkLockerFactory<C> chunkLockerFactory = new ChunkLockerFactory<>();

	private boolean consolidating;
	private boolean cleaning;

	CubeConsolidationController(Eventloop eventloop,
			CubeDiffScheme<D> cubeDiffScheme, Cube cube,
			OTStateManager<K, D> stateManager,
			AggregationChunkStorage<C> aggregationChunkStorage,
			Map<Aggregation, String> aggregationsMapReversed) {
		this.eventloop = eventloop;
		this.cubeDiffScheme = cubeDiffScheme;
		this.cube = cube;
		this.stateManager = stateManager;
		this.aggregationChunkStorage = aggregationChunkStorage;
		this.aggregationsMapReversed = aggregationsMapReversed;
	}

	public static <K, D, C> CubeConsolidationController<K, D, C> create(Eventloop eventloop,
			CubeDiffScheme<D> cubeDiffScheme,
			Cube cube,
			OTStateManager<K, D> stateManager,
			AggregationChunkStorage<C> aggregationChunkStorage) {
		Map<Aggregation, String> map = new IdentityHashMap<>();
		for (String aggregationId : cube.getAggregationIds()) {
			map.put(cube.getAggregation(aggregationId), aggregationId);
		}
		return new CubeConsolidationController<>(eventloop, cubeDiffScheme, cube, stateManager, aggregationChunkStorage, map);
	}

	@Deprecated
	public CubeConsolidationController<K, D, C> withStrategy(Supplier<Function<Aggregation, Promise<AggregationDiff>>> strategy) {
		return withLockerStrategy(() -> (aggregation, chunkLocker) -> strategy.get().apply(aggregation));
	}

	public CubeConsolidationController<K, D, C> withLockerStrategy(Supplier<BiFunction<Aggregation, ChunkLocker<Object>, Promise<AggregationDiff>>> strategy) {
		this.strategy = strategy;
		return this;
	}

	public CubeConsolidationController<K, D, C> withChunkLockerFactory(Function<String, ChunkLocker<C>> factory) {
		this.chunkLockerFactory = new ChunkLockerFactory<>(factory);
		return this;
	}

	private final AsyncSupplier<Void> consolidate = reuse(this::doConsolidate);
	private final AsyncSupplier<Void> cleanupIrrelevantChunks = reuse(this::doCleanupIrrelevantChunks);

	@SuppressWarnings("UnusedReturnValue")
	public Promise<Void> consolidate() {
		return consolidate.get();
	}

	@SuppressWarnings("UnusedReturnValue")
	public Promise<Void> cleanupIrrelevantChunks() {
		return cleanupIrrelevantChunks.get();
	}

	Promise<Void> doConsolidate() {
		checkState(!cleaning, "Cannot consolidate and clean up irrelevant chunks at the same time");
		consolidating = true;
		return Promise.complete()
				.then(stateManager::sync)
				.then(wrapExceptionFn(e -> new CubeException("Failed to synchronize state prior to consolidation", e)))
				.then(() -> cube.consolidate(aggregation -> {
							String aggregationId = aggregationsMapReversed.get(aggregation);
							ChunkLocker<Object> locker = chunkLockerFactory.ensureLocker(aggregationId);
							return strategy.get().apply(aggregation, locker);
						})
						.whenComplete(promiseConsolidateImpl.recordStats()))
				.whenResult(this::cubeDiffJmx)
				.whenComplete(this::logCubeDiff)
				.then(cubeDiff -> {
					if (cubeDiff.isEmpty()) return Promise.complete();
					return Promise.complete()
							.then(() -> aggregationChunkStorage.finish(addedChunks(cubeDiff)))
							.then(wrapExceptionFn(e -> new CubeException("Failed to finalize chunks in storage", e)))
							.whenResult(() -> stateManager.add(cubeDiffScheme.wrap(cubeDiff)))
							.then(() -> stateManager.sync()
									.then(wrapExceptionFn(e -> new CubeException(
											"Failed to synchronize state after consolidation, resetting", e))))
							.whenException(e -> stateManager.reset())
							.whenComplete(toLogger(logger, thisMethod(), cubeDiff));
				})
				.then((result, e) -> chunkLockerFactory.tryReleaseAll()
						.then(() -> Promise.of(result, e)))
				.whenComplete(promiseConsolidate.recordStats())
				.whenComplete(toLogger(logger, thisMethod(), stateManager))
				.whenComplete(() -> consolidating = false);
	}

	private Promise<Void> doCleanupIrrelevantChunks() {
		checkState(!consolidating, "Cannot consolidate and clean up irrelevant chunks at the same time");
		cleaning = true;
		return stateManager.sync()
				.then(wrapExceptionFn(e -> new CubeException("Failed to synchronize state prior to cleaning up irrelevant chunks", e)))
				.then(() -> {
					Map<String, Set<AggregationChunk>> irrelevantChunks = cube.getIrrelevantChunks();
					if (irrelevantChunks.isEmpty()) {
						logger.info("Found no irrelevant chunks");
						return Promise.complete();
					}
					logger.info("Removing irrelevant chunks: " + irrelevantChunks.keySet());
					Map<String, AggregationDiff> diffMap = transformMap(irrelevantChunks,
							chunksToRemove -> AggregationDiff.of(emptySet(), chunksToRemove));
					CubeDiff cubeDiff = CubeDiff.of(diffMap);
					cubeDiffJmx(cubeDiff);
					stateManager.add(cubeDiffScheme.wrap(cubeDiff));
					return stateManager.sync()
							.then(wrapExceptionFn(e -> new CubeException(
									"Failed to synchronize state after cleaning up irrelevant chunks, resetting", e)))
							.whenException(e -> stateManager.reset());
				})
				.whenComplete(promiseCleanupIrrelevantChunks.recordStats())
				.whenComplete(toLogger(logger, thisMethod(), stateManager))
				.whenComplete(() -> cleaning = false);
	}

	private void cubeDiffJmx(CubeDiff cubeDiff) {
		long curAddedChunks = 0;
		long curAddedChunksRecords = 0;
		long curRemovedChunks = 0;
		long curRemovedChunksRecords = 0;

		for (String key : cubeDiff.keySet()) {
			AggregationDiff aggregationDiff = cubeDiff.get(key);
			curAddedChunks += aggregationDiff.getAddedChunks().size();
			for (AggregationChunk aggregationChunk : aggregationDiff.getAddedChunks()) {
				curAddedChunksRecords += aggregationChunk.getCount();
			}

			curRemovedChunks += aggregationDiff.getRemovedChunks().size();
			for (AggregationChunk aggregationChunk : aggregationDiff.getRemovedChunks()) {
				curRemovedChunksRecords += aggregationChunk.getCount();
			}
		}

		addedChunks.recordValue(curAddedChunks);
		addedChunksRecords.recordValue(curAddedChunksRecords);
		removedChunks.recordValue(curRemovedChunks);
		removedChunksRecords.recordValue(curRemovedChunksRecords);
	}

	@SuppressWarnings("unchecked")
	private static <C> Set<C> addedChunks(CubeDiff cubeDiff) {
		return cubeDiff.addedChunks().map(id -> (C) id).collect(toSet());
	}

	private void logCubeDiff(CubeDiff cubeDiff, Exception e) {
		if (e != null) logger.warn("Consolidation failed", e);
		else if (cubeDiff.isEmpty()) logger.info("Previous consolidation did not merge any chunks");
		else logger.info("Consolidation finished. Launching consolidation task again.");
	}

	@JmxAttribute
	public ValueStats getRemovedChunks() {
		return removedChunks;
	}

	@JmxAttribute
	public ValueStats getAddedChunks() {
		return addedChunks;
	}

	@JmxAttribute
	public ValueStats getRemovedChunksRecords() {
		return removedChunksRecords;
	}

	@JmxAttribute
	public ValueStats getAddedChunksRecords() {
		return addedChunksRecords;
	}

	@JmxAttribute
	public PromiseStats getPromiseConsolidate() {
		return promiseConsolidate;
	}

	@JmxAttribute
	public PromiseStats getPromiseConsolidateImpl() {
		return promiseConsolidateImpl;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanupIrrelevantChunks() {
		return promiseCleanupIrrelevantChunks;
	}

	@JmxOperation
	public void consolidateNow() {
		consolidate();
	}

	@JmxOperation
	public void cleanupIrrelevantChunksNow() {
		cleanupIrrelevantChunks();
	}

	@NotNull
	@Override
	public Eventloop getEventloop() {
		return eventloop;
	}
}
