package com.iso2cso.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

/**
 * Multithreaded ISO -> CSO (CISO v1) encoder.
 *
 * Format: 24-byte header, (blocks + 1) little-endian u32 index entries, then block data.
 * Each index entry is (offset >> align); bit 31 set means the block is stored uncompressed.
 * Compressed blocks are raw deflate streams (no zlib header).
 *
 * Pure Java with no Android dependencies so it can be unit tested on the JVM.
 */
public final class CsoEncoder {

    public interface Progress {
        /** Called from the encoding thread. */
        void onProgress(long bytesRead, long totalBytes, long bytesWritten);
    }

    public static final class Result {
        public final long inputBytes;
        public final long outputBytes;
        public final long millis;

        Result(long inputBytes, long outputBytes, long millis) {
            this.inputBytes = inputBytes;
            this.outputBytes = outputBytes;
            this.millis = millis;
        }
    }

    /** Block size the real PSP (and every CSO reader) understands. */
    public static final int BLOCK_PSP = 2048;
    /** Bigger blocks compress noticeably better; PPSSPP reads them fine, real PSP hardware does not. */
    public static final int BLOCK_PPSSPP = 16384;

    private static final int HEADER_SIZE = 0x18;
    private static final int PLAIN_FLAG = 0x80000000;
    /** Roughly how much input each worker task handles at a time. */
    private static final int CHUNK_BYTES = 512 * 1024;
    private static final int OUT_BUFFER_BYTES = 4 * 1024 * 1024;

    private final int blockSize;
    private final boolean exhaustive;
    private final int threads;

    /**
     * @param blockSize  power of two, >= 2048
     * @param exhaustive also try alternative deflate strategies per block and keep the smallest
     * @param threads    worker thread count
     */
    public CsoEncoder(int blockSize, boolean exhaustive, int threads) {
        if (blockSize < 2048 || Integer.bitCount(blockSize) != 1) {
            throw new IllegalArgumentException("block size must be a power of two >= 2048");
        }
        this.blockSize = blockSize;
        this.exhaustive = exhaustive;
        this.threads = Math.max(1, threads);
    }

    public Result encode(FileChannel in, FileChannel out, AtomicBoolean cancel, Progress progress)
            throws IOException {
        long start = System.currentTimeMillis();
        final long total = in.size();
        if (total <= 0) throw new IOException("Input file is empty");
        long blocksL = (total + blockSize - 1) / blockSize;
        if (blocksL > Integer.MAX_VALUE - 2) throw new IOException("Input file is too large");
        final int blocks = (int) blocksL;
        final int align = computeAlign(blocks);
        final long alignMask = (1L << align) - 1;
        final int[] index = new int[blocks + 1];
        // Chunks are written in submission order, so block data stays sequential.

        long pos = HEADER_SIZE + 4L * (blocks + 1);
        pos = (pos + alignMask) & ~alignMask;

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'C', 'I', 'S', 'O'});
        header.putInt(HEADER_SIZE);
        header.putLong(total);
        header.putInt(blockSize);
        header.put((byte) 1); // version
        header.put((byte) align);
        header.putShort((short) 0);
        header.flip();
        out.truncate(0);
        writeFully(out, header, 0);
        // Index is filled in at the end; skip to the first data offset.
        out.position(pos);

        final int chunkBlocks = Math.max(1, CHUNK_BYTES / blockSize);
        final int maxInFlight = threads * 3;
        final ThreadLocal<Workspace> workspaces = new ThreadLocal<Workspace>() {
            @Override
            protected Workspace initialValue() {
                return new Workspace(blockSize, exhaustive);
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private int n;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cso-worker-" + (n++));
                t.setDaemon(true);
                return t;
            }
        });
        ArrayDeque<Future<Chunk>> inFlight = new ArrayDeque<>();
        ByteBuffer outBuf = ByteBuffer.allocateDirect(OUT_BUFFER_BYTES);
        byte[] pad = new byte[1 << align];
        long readPos = 0;
        int nextBlock = 0;
        try {
            while (nextBlock < blocks || !inFlight.isEmpty()) {
                if (cancel.get()) throw new InterruptedIOException("Cancelled");
                if (nextBlock < blocks && inFlight.size() < maxInFlight) {
                    final int first = nextBlock;
                    final int count = Math.min(chunkBlocks, blocks - first);
                    final byte[] data = new byte[count * blockSize]; // zero padded tail
                    int want = (int) Math.min((long) data.length, total - readPos);
                    readFully(in, ByteBuffer.wrap(data, 0, want), readPos);
                    readPos += want;
                    nextBlock += count;
                    inFlight.add(pool.submit(new Callable<Chunk>() {
                        @Override
                        public Chunk call() {
                            return workspaces.get().compress(data, first, count);
                        }
                    }));
                    continue;
                }
                Chunk chunk = await(inFlight.removeFirst());
                for (int i = 0; i < chunk.count; i++) {
                    index[chunk.first + i] = (int) (pos >> align) | (chunk.plain[i] ? PLAIN_FLAG : 0);
                    pos = put(out, outBuf, chunk.data[i], 0, chunk.length[i], pos);
                    long aligned = (pos + alignMask) & ~alignMask;
                    if (aligned != pos) pos = put(out, outBuf, pad, 0, (int) (aligned - pos), pos);
                }
                if (progress != null) progress.onProgress(Math.min(readPos, total), total, pos);
            }
            index[blocks] = (int) (pos >> align);
            flush(out, outBuf);

            ByteBuffer idx = ByteBuffer.allocate(4 * (blocks + 1)).order(ByteOrder.LITTLE_ENDIAN);
            idx.asIntBuffer().put(index); // view keeps the buffer's little-endian order
            writeFully(out, idx, HEADER_SIZE);
            out.truncate(pos);
            out.force(false);
        } finally {
            pool.shutdownNow();
        }
        return new Result(total, pos, System.currentTimeMillis() - start);
    }

    /**
     * Index entries hold 31-bit offsets in units of 2^align, so archives that could exceed 2 GiB
     * need a non-zero alignment. Worst case output is every block stored plain plus padding.
     */
    int computeAlign(int blocks) {
        int align = 0;
        while (true) {
            long unit = 1L << align;
            long worst = HEADER_SIZE + 4L * (blocks + 1) + unit
                    + (long) blocks * (blockSize + unit - 1);
            if ((worst >> align) <= 0x7FFFFFFFL) return align;
            align++;
        }
    }

    private static Chunk await(Future<Chunk> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c);
        }
    }

    private static long put(FileChannel out, ByteBuffer buf, byte[] src, int off, int len, long pos)
            throws IOException {
        while (len > 0) {
            if (!buf.hasRemaining()) flush(out, buf);
            int n = Math.min(len, buf.remaining());
            buf.put(src, off, n);
            off += n;
            len -= n;
            pos += n;
        }
        return pos;
    }

    private static void flush(FileChannel out, ByteBuffer buf) throws IOException {
        buf.flip();
        while (buf.hasRemaining()) out.write(buf);
        buf.clear();
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) position += ch.write(buf, position);
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, position);
            if (n < 0) throw new IOException("Unexpected end of input");
            position += n;
        }
    }

    private static final class Chunk {
        final int first;
        final int count;
        final byte[][] data;
        final int[] length;
        final boolean[] plain;

        Chunk(int first, int count) {
            this.first = first;
            this.count = count;
            this.data = new byte[count][];
            this.length = new int[count];
            this.plain = new boolean[count];
        }

    }

    /** Per-thread deflaters and scratch buffers. */
    private static final class Workspace {
        private final int blockSize;
        private final Deflater[] deflaters;
        private final byte[] scratch;

        Workspace(int blockSize, boolean exhaustive) {
            this.blockSize = blockSize;
            int[] strategies = exhaustive
                    ? new int[] {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED}
                    : new int[] {Deflater.DEFAULT_STRATEGY};
            deflaters = new Deflater[strategies.length];
            for (int i = 0; i < strategies.length; i++) {
                deflaters[i] = new Deflater(Deflater.BEST_COMPRESSION, true);
                deflaters[i].setStrategy(strategies[i]);
            }
            scratch = new byte[blockSize + 64];
        }

        Chunk compress(byte[] data, int first, int count) {
            Chunk c = new Chunk(first, count);
            for (int i = 0; i < count; i++) {
                int off = i * blockSize;
                int best = Integer.MAX_VALUE;
                byte[] bestBytes = null;
                for (Deflater d : deflaters) {
                    d.reset();
                    d.setInput(data, off, blockSize);
                    d.finish();
                    int n = 0;
                    // Anything that doesn't shrink below a block is stored plain, so cap the output.
                    while (!d.finished() && n < scratch.length) {
                        n += d.deflate(scratch, n, scratch.length - n);
                    }
                    if (d.finished() && n < best) {
                        best = n;
                        bestBytes = java.util.Arrays.copyOf(scratch, n);
                    }
                }
                if (bestBytes != null && best < blockSize) {
                    c.data[i] = bestBytes;
                    c.length[i] = best;
                } else {
                    byte[] raw = new byte[blockSize];
                    System.arraycopy(data, off, raw, 0, blockSize);
                    c.data[i] = raw;
                    c.length[i] = blockSize;
                    c.plain[i] = true;
                }
            }
            return c;
        }
    }
}
