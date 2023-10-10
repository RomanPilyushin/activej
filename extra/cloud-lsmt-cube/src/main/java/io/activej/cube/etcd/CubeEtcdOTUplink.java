package io.activej.cube.etcd;

import io.activej.common.builder.AbstractBuilder;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.tuple.Tuple2;
import io.activej.cube.CubeStructure;
import io.activej.cube.aggregation.AggregationChunk;
import io.activej.cube.aggregation.ot.AggregationDiff;
import io.activej.cube.linear.MeasuresValidator;
import io.activej.cube.ot.CubeDiff;
import io.activej.etcd.EtcdEventProcessor;
import io.activej.etcd.EtcdListener;
import io.activej.etcd.EtcdUtils;
import io.activej.etcd.codec.key.EtcdKeyCodecs;
import io.activej.etcd.codec.kv.EtcdKVCodec;
import io.activej.etcd.codec.kv.EtcdKVCodecs;
import io.activej.etcd.codec.prefix.EtcdPrefixCodec;
import io.activej.etcd.codec.prefix.EtcdPrefixCodecs;
import io.activej.etcd.codec.value.EtcdValueCodec;
import io.activej.etcd.exception.MalformedEtcdDataException;
import io.activej.etl.LogDiff;
import io.activej.etl.LogPositionDiff;
import io.activej.etl.json.JsonCodecs;
import io.activej.multilog.LogPosition;
import io.activej.ot.uplink.AsyncOTUplink;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.options.DeleteOption;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkNotNull;
import static io.activej.common.Utils.entriesToLinkedHashMap;
import static io.activej.common.Utils.union;
import static io.activej.cube.aggregation.json.JsonCodecs.ofPrimaryKey;
import static io.activej.cube.etcd.EtcdUtils.saveCubeLogDiff;
import static io.activej.cube.linear.CubeMySqlOTUplink.NO_MEASURE_VALIDATION;
import static io.activej.etcd.EtcdUtils.*;
import static io.activej.json.JsonUtils.fromJson;
import static io.activej.json.JsonUtils.toJson;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.*;

public final class CubeEtcdOTUplink extends AbstractReactive
	implements AsyncOTUplink<Long, LogDiff<CubeDiff>, CubeEtcdOTUplink.UplinkProtoCommit> {

	private static final ByteSequence POS = byteSequenceFrom("pos.");
	private static final ByteSequence CUBE = byteSequenceFrom("cube.");
	private static final EtcdPrefixCodec<String> AGGREGATION_ID_CODEC = EtcdPrefixCodecs.ofTerminatingString('.');

	private final Client client;
	private final ByteSequence root;

	private EtcdPrefixCodec<String> aggregationIdCodec = AGGREGATION_ID_CODEC;
	private Function<String, EtcdKVCodec<Long, AggregationChunk>> chunkCodecsFactory;
	private MeasuresValidator measuresValidator = NO_MEASURE_VALIDATION;
	private ByteSequence prefixPos = POS;
	private ByteSequence prefixCube = CUBE;

	private CubeEtcdOTUplink(Reactor reactor, Client client, ByteSequence root) {
		super(reactor);
		this.client = client;
		this.root = root;
	}

	public static CubeEtcdOTUplink.Builder builder(Reactor reactor, Client client, ByteSequence root) {
		return new CubeEtcdOTUplink(reactor, client, root).new Builder();
	}

	public final class Builder extends AbstractBuilder<CubeEtcdOTUplink.Builder, CubeEtcdOTUplink> {
		private Builder() {}

		public Builder withChunkCodecsFactory(Function<String, EtcdKVCodec<Long, AggregationChunk>> chunkCodecsFactory) {
			checkNotBuilt(this);
			CubeEtcdOTUplink.this.chunkCodecsFactory = chunkCodecsFactory;
			return this;
		}

		public Builder withChunkCodecsFactoryJson(CubeStructure cubeStructure) {
			checkNotBuilt(this);
			Map<String, AggregationChunkJsonEtcdKVCodec> collect = cubeStructure.getAggregationStructures().entrySet().stream()
				.collect(entriesToLinkedHashMap(structure ->
					new AggregationChunkJsonEtcdKVCodec(ofPrimaryKey(structure))));
			return withChunkCodecsFactory(collect::get);
		}

		public Builder withMeasuresValidator(MeasuresValidator measuresValidator) {
			checkNotBuilt(this);
			CubeEtcdOTUplink.this.measuresValidator = measuresValidator;
			return this;
		}

		public Builder withPrefixPos(ByteSequence prefixPos) {
			checkNotBuilt(this);
			CubeEtcdOTUplink.this.prefixPos = prefixPos;
			return this;
		}

		public Builder withPrefixCube(ByteSequence prefixCube) {
			checkNotBuilt(this);
			CubeEtcdOTUplink.this.prefixCube = prefixCube;
			return this;
		}

		public Builder withAggregationIdCodec(EtcdPrefixCodec<String> aggregationIdCodec) {
			checkNotBuilt(this);
			CubeEtcdOTUplink.this.aggregationIdCodec = aggregationIdCodec;
			return this;
		}

		@Override
		protected CubeEtcdOTUplink doBuild() {
			checkNotNull(chunkCodecsFactory, "Chunk codecs factory is required");
			return CubeEtcdOTUplink.this;
		}
	}

	@Override
	public Promise<FetchData<Long, LogDiff<CubeDiff>>> checkout() {
		checkInReactorThread(this);
		return Promise.ofCompletionStage(
			doCheckout(0L)
				.thenApply(response -> {
					Map<String, LogPositionDiff> positions = response.positions.entrySet().stream()
						.collect(entriesToLinkedHashMap(logPosition -> new LogPositionDiff(null, logPosition)));

					CubeDiff cubeDiff = CubeDiff.of(response.chunks.entrySet().stream()
						.collect(entriesToLinkedHashMap(AggregationDiff::of)));

					List<LogDiff<CubeDiff>> diffs = positions.isEmpty() && cubeDiff.getDiffs().isEmpty() ?
						List.of() :
						List.of(LogDiff.of(positions, cubeDiff));
					return new FetchData<>(response.revision, response.revision, diffs);
				}));
	}

	record CubeCheckoutResponse(long revision, Map<String, LogPosition> positions, Map<String, Set<AggregationChunk>> chunks) {}

	@SuppressWarnings("unchecked")
	private CompletableFuture<CubeCheckoutResponse> doCheckout(long revision) {
		return EtcdUtils.checkout(client, revision, new CheckoutRequest[]{
				CheckoutRequest.<String, LogPosition, LinkedHashMap<String, LogPosition>>ofMapEntry(
					root.concat(prefixPos),
					EtcdKVCodecs.ofMapEntry(EtcdKeyCodecs.ofString(), logPositionEtcdCodec()),
					entriesToLinkedHashMap()),
				CheckoutRequest.<Tuple2<String, AggregationChunk>, Map<String, Set<AggregationChunk>>>of(
					root.concat(prefixCube),
					EtcdKVCodecs.ofPrefixedEntry(aggregationIdCodec, chunkCodecsFactory),
					groupingBy(Tuple2::value1, mapping(Tuple2::value2, toSet())))
			},
			(header, objects) -> {
				var logPositions = (Map<String, LogPosition>) objects[0];
				var aggregationChunks = (Map<String, Set<AggregationChunk>>) objects[1];

				for (var entry : aggregationChunks.entrySet()) {
					for (AggregationChunk chunk : entry.getValue()) {
						try {
							measuresValidator.validate(entry.getKey(), chunk.getMeasures());
						} catch (MalformedDataException e) {
							throw new MalformedEtcdDataException(e.getMessage());
						}
					}
				}

				return new CubeCheckoutResponse(header.getRevision(), logPositions, aggregationChunks);
			}
		);
	}

	@Override
	public Promise<FetchData<Long, LogDiff<CubeDiff>>> fetch(Long currentCommitId) {
		checkInReactorThread(this);
		return Promise.ofCompletionStage(client.getKVClient().get(root))
			.then(response -> {
				long targetRevision = response.getKvs().isEmpty() ? response.getHeader().getRevision() : response.getKvs().get(0).getModRevision();
				return doFetch(currentCommitId, targetRevision)
					.map(logDiffs -> new FetchData<>(targetRevision, targetRevision, logDiffs));
			});
	}

	private Promise<List<LogDiff<CubeDiff>>> doFetch(long revisionFrom, long revisionTo) {
		checkInReactorThread(this);
		checkArgument(revisionFrom <= revisionTo);
		if (revisionTo == revisionFrom) return Promise.of(emptyList());
		SettablePromise<List<LogDiff<CubeDiff>>> promise = new SettablePromise<>();
		final AtomicReference<Watch.Watcher> etcdWatcherRef = new AtomicReference<>();
		reactor.startExternalTask();
		etcdWatcherRef.set(EtcdUtils.watch(client, revisionFrom + 1, new WatchRequest[]{
				WatchRequest.<String, LogPosition, Map<String, LogPositionDiff>>ofMapEntry(
					root.concat(prefixPos),
					EtcdKVCodecs.ofMapEntry(EtcdKeyCodecs.ofString(), logPositionEtcdCodec()),
					new EtcdEventProcessor<String, Map.Entry<String, LogPosition>, Map<String, LogPositionDiff>>() {
						@Override
						public Map<String, LogPositionDiff> createEventsAccumulator() {
							return new LinkedHashMap<>();
						}

						@Override
						public void onPut(Map<String, LogPositionDiff> accumulator, Map.Entry<String, LogPosition> entry) {
							accumulator.put(entry.getKey(), new LogPositionDiff(null, entry.getValue()));
						}

						@Override
						public void onDelete(Map<String, LogPositionDiff> accumulator, String key) {
							throw new UnsupportedOperationException();
						}
					}
				),
				WatchRequest.<Tuple2<String, Long>, Tuple2<String, AggregationChunk>, Map<String, AggregationDiff>>of(
					root.concat(prefixCube),
					EtcdKVCodecs.ofPrefixedEntry(aggregationIdCodec, chunkCodecsFactory),
					new EtcdEventProcessor<Tuple2<String, Long>, Tuple2<String, AggregationChunk>, Map<String, AggregationDiff>>() {
						@Override
						public Map<String, AggregationDiff> createEventsAccumulator() {
							return new LinkedHashMap<>();
						}

						@Override
						public void onPut(Map<String, AggregationDiff> accumulator, Tuple2<String, AggregationChunk> kv) {
							accumulator.compute(kv.value1(), (aggregationId, aggregationDiff) ->
								aggregationDiff == null ?
									AggregationDiff.of(Set.of(kv.value2()), Set.of()) :
									AggregationDiff.of(union(aggregationDiff.getAddedChunks(), Set.of(kv.value2())), aggregationDiff.getRemovedChunks()));
						}

						@Override
						public void onDelete(Map<String, AggregationDiff> accumulator, Tuple2<String, Long> key) {
							accumulator.compute(key.value1(), (aggregationId, aggregationDiff) ->
								aggregationDiff == null ?
									AggregationDiff.of(Set.of(), Set.of(AggregationChunk.ofId(key.value2()))) :
									AggregationDiff.of(aggregationDiff.getAddedChunks(), union(aggregationDiff.getRemovedChunks(), Set.of(AggregationChunk.ofId(key.value2())))));
						}
					}
				),
			},
			new EtcdListener<Object[]>() {
				LogDiff<CubeDiff> logDiff = LogDiff.empty();

				@SuppressWarnings("unchecked")
				@Override
				public void onNext(long revision, Object[] operation) throws MalformedEtcdDataException {
					checkArgument(revision <= revisionTo);

					var logPositionDiffs = (Map<String, LogPositionDiff>) operation[0];
					var aggregationDiffs = (Map<String, AggregationDiff>) operation[1];

					for (var entry : aggregationDiffs.entrySet()) {
						for (AggregationChunk addedChunk : entry.getValue().getAddedChunks()) {
							try {
								measuresValidator.validate(entry.getKey(), addedChunk.getMeasures());
							} catch (MalformedDataException e) {
								throw new MalformedEtcdDataException(e.getMessage());
							}
						}
					}

					this.logDiff = LogDiff.reduce(List.of(this.logDiff, LogDiff.of(logPositionDiffs, CubeDiff.of(aggregationDiffs))), CubeDiff::reduce);

					if (revision == revisionTo) {
						reactor.execute(() -> promise.trySet(List.of(logDiff)));
						etcdWatcherRef.get().close();
					}
				}

				@Override
				public void onError(Throwable throwable) {
					reactor.submit(() -> promise.trySetException((Exception) throwable));
				}

				@Override
				public void onCompleted() {
					reactor.completeExternalTask();
				}
			}));
		return promise;
	}

	@Override
	public Promise<UplinkProtoCommit> createProtoCommit(Long parent, List<LogDiff<CubeDiff>> diffs, long parentLevel) {
		checkInReactorThread(this);
		return Promise.of(new UplinkProtoCommit(parent, diffs));
	}

	@Override
	public Promise<FetchData<Long, LogDiff<CubeDiff>>> push(UplinkProtoCommit protoCommit) {
		checkInReactorThread(this);
		return Promise.ofCompletionStage(
				executeTxnOps(client, root, txnOps -> {
					touchTimestamp(txnOps, ByteSequence.EMPTY, reactor);
					for (LogDiff<CubeDiff> diff : protoCommit.diffs) {
						saveCubeLogDiff(prefixPos, prefixCube, aggregationIdCodec, chunkCodecsFactory, txnOps, diff);
					}
				})
			)
			.then(txnResponse ->
				doFetch(protoCommit.parentRevision(), txnResponse.getHeader().getRevision() - 1)
					.map(logDiffs -> new FetchData<>(txnResponse.getHeader().getRevision(), txnResponse.getHeader().getRevision(), logDiffs)));
	}

	public record UplinkProtoCommit(long parentRevision, List<LogDiff<CubeDiff>> diffs) {}

	public static EtcdValueCodec<LogPosition> logPositionEtcdCodec() {
		return new EtcdValueCodec<>() {
			@Override
			public ByteSequence encodeValue(LogPosition value) {
				return byteSequenceFrom(toJson(JsonCodecs.ofLogPosition(), value));
			}

			@Override
			public LogPosition decodeValue(ByteSequence byteSequence) throws MalformedEtcdDataException {
				try {
					return fromJson(JsonCodecs.ofLogPosition(), byteSequence.toString());
				} catch (MalformedDataException e) {
					throw new MalformedEtcdDataException(e.getMessage());
				}
			}
		};
	}

	public void delete() throws ExecutionException, InterruptedException {
		client.getKVClient()
			.delete(root,
				DeleteOption.builder()
					.isPrefix(true)
					.build())
			.get();
		client.getKVClient()
			.put(root, TOUCH_TIMESTAMP_CODEC.encodeValue(reactor.currentTimeMillis()))
			.get();
	}

}