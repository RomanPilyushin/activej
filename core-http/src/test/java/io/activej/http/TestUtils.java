package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.MemSize;
import io.activej.csp.consumer.AbstractChannelConsumer;
import io.activej.csp.process.transformer.ChannelTransformer;
import io.activej.csp.process.transformer.ChannelTransformers;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static io.activej.bytebuf.ByteBufStrings.decodeAscii;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

public class TestUtils {

	public static void assertEmpty(InputStream inputStream) {
		try {
			assertEquals(-1, inputStream.read());
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			try {
				inputStream.close();
			} catch (IOException e) {
				//noinspection ThrowFromFinallyBlock
				throw new AssertionError(e);
			}
		}
	}

	public static void readFully(InputStream is, byte[] bytes) {
		DataInputStream dis = new DataInputStream(is);
		try {
			dis.readFully(bytes);
		} catch (IOException e) {
			throw new RuntimeException("Could not read " + new String(bytes, UTF_8), e);
		}
	}

	public static class AssertingConsumer extends AbstractChannelConsumer<ByteBuf> {
		private final ByteBufs bufs = new ByteBufs();

		public boolean executed = false;
		private byte @Nullable [] expectedByteArray;
		private @Nullable String expectedString;
		private @Nullable ByteBuf expectedBuf;
		private @Nullable Class<? extends Exception> expectedExceptionType;
		private @Nullable Consumer<Exception> exceptionValidator;

		public void setExpectedByteArray(byte @Nullable [] expectedByteArray) {
			this.expectedByteArray = expectedByteArray;
		}

		public void setExpectedString(@Nullable String expectedString) {
			this.expectedString = expectedString;
		}

		public void setExpectedBuf(@Nullable ByteBuf expectedBuf) {
			this.expectedBuf = expectedBuf;
		}

		public void setExpectedExceptionType(@Nullable Class<? extends Exception> expectedExceptionType) {
			this.expectedExceptionType = expectedExceptionType;
		}

		public void setExceptionValidator(@Nullable Consumer<Exception> exceptionValidator) {
			this.exceptionValidator = exceptionValidator;
		}

		public void reset() {
			expectedBuf = null;
			expectedByteArray = null;
			expectedExceptionType = null;
			expectedString = null;
			exceptionValidator = null;
			executed = false;
		}

		@Override
		protected Promise<Void> doAccept(@Nullable ByteBuf value) {
			if (value != null) {
				bufs.add(value);
			} else {
				ByteBuf actualBuf = bufs.takeRemaining();

				try {
					if (expectedByteArray != null) {
						byte[] actualByteArray = actualBuf.getArray();
						assertArrayEquals(expectedByteArray, actualByteArray);
					}
					if (expectedString != null) {
						String actualString = decodeAscii(actualBuf.array(), actualBuf.head(), actualBuf.readRemaining());
						assertEquals(expectedString, actualString);
					}
					if (expectedBuf != null) {
						assertArrayEquals(expectedBuf.getArray(), actualBuf.getArray());
						expectedBuf.recycle();
						expectedBuf = null;
					}
				} finally {
					actualBuf.recycle();
				}

				executed = true;
			}
			return Promise.complete();
		}

		@Override
		protected void onCleanup() {
			bufs.recycle();
			if (expectedBuf != null) {
				expectedBuf.recycle();
			}
		}

		@Override
		protected void onClosed(Exception e) {
			executed = true;
			if (expectedExceptionType != null) {
				assertThat(e, instanceOf(expectedExceptionType));
				return;
			}
			if (exceptionValidator != null) {
				exceptionValidator.accept(e);
				return;
			}
			throw new AssertionError(e);
		}
	}

	private static final Random RANDOM = ThreadLocalRandom.current();

	public static <T> Consumer<T> failOnItem() {
		return $ -> fail();
	}

	public static ChannelTransformer<ByteBuf, ByteBuf> chunker() {
		MemSize min = MemSize.of(RANDOM.nextInt(5) + 1);
		return ChannelTransformers.chunkBytes(min, min.map(length -> length * 2));
	}

	public static byte[] randomBytes(int size) {
		byte[] bytes = new byte[size];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	public static ByteBuf closeUnmasked() {
		return ByteBuf.wrapForReading(new byte[]{(byte) 0x88, 0x02, 0x03, (byte) 0xe8});
	}

	public static ByteBuf closeMasked() {
		return ByteBuf.wrapForReading(new byte[]{(byte) 0x88, (byte) 0x82, 0x12, 0x34, 0x56, 0x78, 0x11, (byte) 0xdc});
	}

	public static ChannelSupplier<ByteBuf> chunkedByByte(ChannelSupplier<ByteBuf> supplier) {
		return supplier.transformWith(ChannelTransformers.chunkBytes(MemSize.bytes(1), MemSize.bytes(1)));
	}
}
