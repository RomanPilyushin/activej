package io.activej.fs.cluster;

import io.activej.async.function.AsyncConsumer;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.eventloop.Eventloop;
import io.activej.fs.FileMetadata;
import io.activej.fs.FileSystem;
import io.activej.fs.IFileSystem;
import io.activej.fs.exception.FileSystemException;
import io.activej.fs.tcp.FileSystemServer;
import io.activej.fs.tcp.RemoteFileSystem;
import io.activej.net.AbstractReactiveServer;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static io.activej.common.collection.CollectionUtils.union;
import static io.activej.common.collection.CollectorUtils.toLinkedHashMap;
import static io.activej.common.exception.FatalErrorHandlers.rethrow;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.stream.Collectors.*;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

public final class ClusterFileSystemTest {
	public static final int CLIENT_SERVER_PAIRS = 10;
	public static final int REPLICATION_COUNT = 4;

	@Rule
	public final TemporaryFolder tmpFolder = new TemporaryFolder();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final List<Path> serverStorages = new ArrayList<>();

	private ExecutorService executor;
	private List<AbstractReactiveServer> servers;
	private Path clientStorage;
	private IDiscoveryService discoveryService;
	private FileSystemPartitions partitions;
	private ClusterFileSystem client;

	@Before
	public void setup() throws IOException, ExecutionException, InterruptedException {
		executor = Executors.newSingleThreadExecutor();
		servers = new ArrayList<>(CLIENT_SERVER_PAIRS);
		clientStorage = Paths.get(tmpFolder.newFolder("client").toURI());

		Files.createDirectories(clientStorage);

		Map<Object, IFileSystem> partitions = new HashMap<>(CLIENT_SERVER_PAIRS);

		NioReactor reactor = Reactor.getCurrentReactor();

		for (int i = 0; i < CLIENT_SERVER_PAIRS; i++) {
			int port = getFreePort();
			InetSocketAddress address = new InetSocketAddress("localhost", port);

			partitions.put("server_" + i, RemoteFileSystem.create(reactor, address));

			Path path = Paths.get(tmpFolder.newFolder("storage_" + i).toURI());
			serverStorages.add(path);
			Files.createDirectories(path);

			Eventloop serverEventloop = Eventloop.builder()
				.withFatalErrorHandler(rethrow())
				.build();
			serverEventloop.keepAlive(true);
			FileSystem fileSystem = FileSystem.create(serverEventloop, executor, path);
			CompletableFuture<Void> startFuture = serverEventloop.submit(fileSystem::start);
			AbstractReactiveServer server = FileSystemServer.builder(serverEventloop, fileSystem)
				.withListenPort(port)
				.build();
			CompletableFuture<Void> listenFuture = serverEventloop.submit(() -> {
				try {
					server.listen();
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
			servers.add(server);
			new Thread(serverEventloop).start();
			startFuture.get();
			listenFuture.get();
		}

		partitions.put("dead_one", RemoteFileSystem.create(reactor, new InetSocketAddress("localhost", 5555)));
		partitions.put("dead_two", RemoteFileSystem.create(reactor, new InetSocketAddress("localhost", 5556)));
		partitions.put("dead_three", RemoteFileSystem.create(reactor, new InetSocketAddress("localhost", 5557)));

		discoveryService = IDiscoveryService.constant(partitions);
		this.partitions = FileSystemPartitions.create(reactor, discoveryService);
		client = ClusterFileSystem.builder(reactor, this.partitions)
			.withReplicationCount(REPLICATION_COUNT) // there are those 3 dead nodes added above
			.build();
		await(this.partitions.start());
		await(this.client.start());
	}

	@After
	public void tearDown() {
		waitForServersToStop();
	}

	@Test
	public void testUpload() throws IOException {
		String content = "test content of the file";
		String resultFile = "file.txt";

		ByteBuf value = ByteBuf.wrapForReading(content.getBytes(UTF_8));
		await(client.upload(resultFile)
			.then(ChannelSuppliers.ofValue(value)::streamTo));

		int uploaded = 0;
		for (Path path : serverStorages) {
			Path resultPath = path.resolve(resultFile);
			if (Files.exists(resultPath)) {
				assertEquals(Files.readString(resultPath), content);
				uploaded++;
			}
		}
		assertEquals(4, uploaded); // replication count

	}

	@Test
	public void testDownload() throws IOException {
		int numOfServer = 3;
		String file = "the_file.txt";
		String content = "another test content of the file";

		Files.writeString(serverStorages.get(numOfServer).resolve(file), content);

		await(ChannelSuppliers.ofPromise(client.download(file))
			.streamTo(ChannelFileWriter.open(newCachedThreadPool(), clientStorage.resolve(file))));

		assertEquals(Files.readString(clientStorage.resolve(file)), content);
	}

	@Test
	public void testUploadSelector() throws IOException {
		String content = "test content of the file";
		ByteBuf data = ByteBuf.wrapForReading(content.getBytes(UTF_8));

		partitions = FileSystemPartitions.builder(partitions.getReactor(), discoveryService)
			.withServerSelector((fileName, serverKeys) -> {
				String firstServer = fileName.contains("1") ?
					"server_1" :
					fileName.contains("2") ?
						"server_2" :
						fileName.contains("3") ?
							"server_3" :
							"server_0";
				return serverKeys.stream()
					.map(String.class::cast)
					.sorted(Comparator.comparing(key -> key.equals(firstServer) ? -1 : 1))
					.collect(toList());
			})
			.build();

		client = ClusterFileSystem.builder(client.getReactor(), partitions)
			.withDeadPartitionsThreshold(3)
			.withMinUploadTargets(1)
			.withMaxUploadTargets(1)
			.build();
		await(partitions.start());
		await(client.start());

		String[] files = {"file_1.txt", "file_2.txt", "file_3.txt", "other.txt"};

		await(Promises.all(Arrays.stream(files).map(f ->
		{
			ByteBuf value = data.slice();
			return ChannelSuppliers.ofValue(value)
				.streamTo(ChannelConsumers.ofPromise(client.upload(f)));
		})));

		assertEquals(Files.readString(serverStorages.get(1).resolve("file_1.txt")), content);
		assertEquals(Files.readString(serverStorages.get(2).resolve("file_2.txt")), content);
		assertEquals(Files.readString(serverStorages.get(3).resolve("file_3.txt")), content);
		assertEquals(Files.readString(serverStorages.get(0).resolve("other.txt")), content);
	}

	@Test
	@Ignore("this test uses lots of local sockets (and all of them are in TIME_WAIT state after it for a minute) so HTTP tests after it may fail indefinitely")
	public void testUploadALot() throws IOException {
		String content = "test content of the file";
		ByteBuf data = ByteBuf.wrapForReading(content.getBytes(UTF_8));

		await(Promises.sequence(IntStream.range(0, 1_000)
			.mapToObj(i ->
				() -> {
					ByteBuf value = data.slice();
					return ChannelSuppliers.ofValue(value)
						.streamTo(ChannelConsumers.ofPromise(client.upload("file_uploaded_" + i + ".txt")));
				})));

		for (int i = 0; i < 1000; i++) {
			int replicasCount = 0;
			for (Path path : serverStorages) {
				Path targetPath = path.resolve("file_uploaded_" + i + ".txt");
				if (Files.exists(targetPath) && Arrays.equals(content.getBytes(), readAllBytes(targetPath))) {
					replicasCount++;
				}
			}
			assertEquals(client.getMinUploadTargets(), replicasCount);
		}
	}

	@Test
	public void testNotEnoughUploads() {
		int allClientsSize = partitions.getPartitions().size();
		client.setReplicationCount(allClientsSize);

		ByteBuf value = ByteBuf.wrapForReading("whatever, blah-blah".getBytes(UTF_8));
		Exception exception = awaitException(ChannelSuppliers.ofValue(value)
			.streamTo(ChannelConsumers.ofPromise(client.upload("file_uploaded.txt"))));

		assertThat(exception, instanceOf(FileSystemException.class));
		assertThat(exception.getMessage(), containsString("Didn't connect to enough partitions"));
	}

	@Test
	public void downloadNonExisting() {
		String fileName = "i_dont_exist.txt";

		Exception exception = awaitException(ChannelSuppliers.ofPromise(client.download(fileName))
			.streamTo(ChannelConsumers.ofAsyncConsumer(AsyncConsumer.of(ByteBuf::recycle))));

		assertThat(exception, instanceOf(FileSystemException.class));
		assertThat(exception.getMessage(), containsString(fileName));
	}

	@Test
	public void testCopy() throws IOException {
		String source = "the_file.txt";
		String target = "new_file.txt";
		String content = "test content of the file";

		List<Path> paths = new ArrayList<>(serverStorages);
		Collections.shuffle(paths);

		for (Path path : paths.subList(0, REPLICATION_COUNT)) {
			Files.writeString(path.resolve(source), content);
		}

		await(client.copy(source, target));
		ByteBuf result = await(client.download(target)
			.then(supplier -> supplier.toCollector(ByteBufs.collector())));

		assertEquals(content, result.asString(UTF_8));

		int copies = 0;
		for (Path path : paths) {
			Path targetPath = path.resolve(target);
			if (Files.exists(targetPath) && Arrays.equals(content.getBytes(), Files.readAllBytes(targetPath))) {
				copies++;
			}
		}

		assertEquals(REPLICATION_COUNT, copies);
	}

	@Test
	public void testCopyNotEnoughPartitions() throws IOException {
		int numOfServers = REPLICATION_COUNT - 1;
		String source = "the_file.txt";
		String target = "new_file.txt";
		String content = "test content of the file";

		List<Path> paths = new ArrayList<>(serverStorages);
		Collections.shuffle(paths);

		for (Path path : paths.subList(0, numOfServers)) {
			Files.writeString(path.resolve(source), content);
		}

		await(client.copy(source, target));

		int copies = 0;
		for (Path path : paths) {
			Path targetPath = path.resolve(target);
			if (Files.exists(targetPath) && Arrays.equals(content.getBytes(), Files.readAllBytes(targetPath))) {
				copies++;
			}
		}

		assertEquals(REPLICATION_COUNT, copies);
	}

	@Test
	public void testCopyAllSingleFile() throws IOException {
		doTestCopyAll(1);
	}

	@Test
	public void testCopyAllThreeFiles() throws IOException {
		doTestCopyAll(3);
	}

	@Test
	public void testCopyAllTenFiles() throws IOException {
		doTestCopyAll(10);
	}

	@Test
	public void testCopyAllManyFiles() throws IOException {
		doTestCopyAll(100);
	}

	@Test
	public void testCopyAllNotEnoughPartitions() throws IOException {
		int numberOfServers = REPLICATION_COUNT - 1;
		Map<String, String> sourceToTarget = IntStream.range(0, 10).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String source : sourceToTarget.keySet()) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + source;
			for (Path path : paths.subList(0, numberOfServers)) {
				Files.writeString(path.resolve(source), content);
			}
		}

		await(client.copyAll(sourceToTarget));

		Map<String, Integer> copies = sourceToTarget.keySet().stream()
			.collect(toLinkedHashMap($ -> 0));
		for (Map.Entry<String, String> entry : sourceToTarget.entrySet()) {
			String source = entry.getKey();
			for (Path path : paths) {
				Path targetPath = path.resolve(entry.getValue());
				if (Files.exists(targetPath) &&
					Arrays.equals((contentPrefix + source).getBytes(), Files.readAllBytes(targetPath))
				) {
					copies.computeIfPresent(source, ($, count) -> ++count);
				}
			}
		}

		for (Integer count : copies.values()) {
			assertEquals(Integer.valueOf(REPLICATION_COUNT), count);
		}
	}

	@Test
	public void testCopyAllWithMissingFiles() throws IOException {
		Map<String, String> sourceToTarget = IntStream.range(0, 10).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String source : sourceToTarget.keySet()) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + source;
			for (Path path : paths.subList(0, REPLICATION_COUNT)) {
				Files.writeString(path.resolve(source), content);
			}
		}

		// adding non-existent file to mapping
		String nonexistent = "nonexistent.txt";
		sourceToTarget.put(nonexistent, "new_nonexistent.txt");

		Exception exception = awaitException(client.copyAll(sourceToTarget));
		assertTrue(exception.getMessage().startsWith("Could not download file '" + nonexistent + '\''));
	}

	@Test
	public void testMoveAllSingleFile() throws IOException {
		doTestMoveAll(1);
	}

	@Test
	public void testMoveAllThreeFiles() throws IOException {
		doTestMoveAll(3);
	}

	@Test
	public void testMoveAllTenFiles() throws IOException {
		doTestMoveAll(10);
	}

	@Test
	public void testMoveAllManyFiles() throws IOException {
		doTestMoveAll(100);
	}

	@Test
	public void testMoveAllNotEnoughPartitions() throws IOException {
		int numberOfServers = REPLICATION_COUNT - 1;
		Map<String, String> sourceToTarget = IntStream.range(0, 10).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String source : sourceToTarget.keySet()) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + source;
			for (Path path : paths.subList(0, numberOfServers)) {
				Files.writeString(path.resolve(source), content);
			}
		}

		await(client.moveAll(sourceToTarget));

		Map<String, Integer> copies = sourceToTarget.keySet().stream()
			.collect(toLinkedHashMap($ -> 0));
		for (Map.Entry<String, String> entry : sourceToTarget.entrySet()) {
			String source = entry.getKey();
			for (Path path : paths) {
				Path targetPath = path.resolve(entry.getValue());
				if (Files.exists(targetPath) &&
					Arrays.equals((contentPrefix + source).getBytes(), Files.readAllBytes(targetPath))
				) {
					copies.computeIfPresent(source, ($, count) -> ++count);
				}
			}
		}

		for (Integer count : copies.values()) {
			assertEquals(Integer.valueOf(REPLICATION_COUNT), count);
		}
	}

	@Test
	public void testMoveAllWithMissingFiles() throws IOException {
		Map<String, String> sourceToTarget = IntStream.range(0, 10).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String source : sourceToTarget.keySet()) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + source;
			for (Path path : paths.subList(0, REPLICATION_COUNT)) {
				Files.writeString(path.resolve(source), content);
			}
		}

		// adding non-existent file to mapping
		String nonexistent = "nonexistent.txt";
		sourceToTarget.put(nonexistent, "new_nonexistent.txt");

		Exception exception = awaitException(client.moveAll(sourceToTarget));
		assertTrue(exception.getMessage().startsWith("Could not download file '" + nonexistent + '\''));
	}

	@Test
	public void testInspectAll() throws IOException {
		Set<String> names = IntStream.range(0, 10)
			.mapToObj(i -> "the_file_" + i + ".txt")
			.collect(toSet());
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String name : names) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + name;
			for (Path path : paths.subList(0, ThreadLocalRandom.current().nextInt(3) + 1)) {
				Files.writeString(path.resolve(name), content);
			}
		}

		Map<String, @Nullable FileMetadata> result = await(client.infoAll(names));

		assertEquals(names.size(), result.size());
		for (String name : names) {
			FileMetadata metadata = result.get(name);
			assertNotNull(metadata);
		}
	}

	@Test
	public void testInspectAllWithMissingFiles() throws IOException {
		Set<String> existingNames = IntStream.range(0, 10)
			.mapToObj(i -> "the_file_" + i + ".txt")
			.collect(toSet());
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String name : existingNames) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + name;
			for (Path path : paths.subList(0, ThreadLocalRandom.current().nextInt(3) + 1)) {
				Files.writeString(path.resolve(name), content);
			}
		}

		Set<String> nonExistingNames = IntStream.range(0, 10)
			.mapToObj(i -> "nonexistent_" + i + ".txt")
			.collect(toSet());

		Map<String, @Nullable FileMetadata> result = await(client.infoAll(union(existingNames, nonExistingNames)));

		assertEquals(existingNames.size(), result.size());
		for (String name : existingNames) {
			FileMetadata metadata = result.get(name);
			assertNotNull(metadata);
		}

		for (String name : nonExistingNames) {
			assertFalse(result.containsKey(name));
			assertNull(result.get(name));
		}
	}

	private void doTestCopyAll(int numberOfFiles) throws IOException {
		Map<String, String> sourceToTarget = IntStream.range(0, numberOfFiles).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		doActionAndAssertFilesAreCopied(sourceToTarget, client::copyAll);
	}

	private void doTestMoveAll(int numberOfFiles) throws IOException {
		Map<String, String> sourceToTarget = IntStream.range(0, numberOfFiles).boxed()
			.collect(toMap(i -> "the_file_" + i + ".txt", i -> "the_new_file_" + i + ".txt"));
		doActionAndAssertFilesAreCopied(sourceToTarget, client::moveAll);

		for (Path serverStorage : serverStorages) {
			for (String s : sourceToTarget.keySet()) {
				if (Files.exists(serverStorage.resolve(s))) {
					fail();
				}
			}
		}
	}

	private void doActionAndAssertFilesAreCopied(Map<String, String> sourceToTarget, AsyncConsumer<Map<String, String>> action) throws IOException {
		String contentPrefix = "test content of the file ";
		List<Path> paths = new ArrayList<>(serverStorages);

		for (String source : sourceToTarget.keySet()) {
			Collections.shuffle(paths); // writing each source to random partitions

			String content = contentPrefix + source;
			for (Path path : paths.subList(0, REPLICATION_COUNT)) {
				Files.writeString(path.resolve(source), content);
			}
		}

		await(action.accept(sourceToTarget));
		List<String> results = await(Promises.toList(sourceToTarget.values().stream()
			.map(target -> client.download(target)
				.then(supplier -> supplier.toCollector(ByteBufs.collector()))
				.map(byteBuf -> byteBuf.asString(UTF_8)))));

		Set<String> expectedContents = sourceToTarget.keySet().stream().map(source -> contentPrefix + source).collect(toSet());

		for (String result : results) {
			assertTrue(expectedContents.contains(result));
			expectedContents.remove(result);
		}
		assertTrue(expectedContents.isEmpty());

		Map<String, String> expected = sourceToTarget.entrySet().stream()
			.collect(toMap(Map.Entry::getValue, entry -> contentPrefix + entry.getKey()));

		for (Map.Entry<String, String> entry : expected.entrySet()) {
			int copies = 0;
			for (Path path : paths) {
				Path targetPath = path.resolve(entry.getKey());
				if (Files.exists(targetPath) &&
					Arrays.equals(entry.getValue().getBytes(), Files.readAllBytes(targetPath))
				) {
					copies++;
				}
			}
			assertEquals(REPLICATION_COUNT, copies);
		}
	}

	private void waitForServersToStop() {
		try {
			for (AbstractReactiveServer server : servers) {
				Eventloop serverEventloop = (Eventloop) server.getReactor();
				if (server.isRunning()) {
					serverEventloop.submit(server::close).get();
				}
				serverEventloop.keepAlive(false);
				Thread serverEventloopThread = serverEventloop.getEventloopThread();
				if (serverEventloopThread != null) {
					serverEventloopThread.join();
				}
			}
			executor.shutdown();
			//noinspection ResultOfMethodCallIgnored
			executor.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		} catch (ExecutionException e) {
			throw new AssertionError(e);
		}
	}

}
