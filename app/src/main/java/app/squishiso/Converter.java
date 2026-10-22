package app.squishiso;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Platform-independent conversion steps: ISO/CSO -> CSO, CSO -> ISO, and read-back verification. */
public final class Converter {

    public enum Phase { CONVERTING, VERIFYING }

    public interface Listener {
        void onProgress(Phase phase, long done, long total, long written);
    }

    public static final class Result {
        public final long inputBytes;
        public final long outputBytes;
        public final long millis;
        public final long crc;

        Result(long inputBytes, long outputBytes, long millis, long crc) {
            this.inputBytes = inputBytes;
            this.outputBytes = outputBytes;
            this.millis = millis;
            this.crc = crc;
        }
    }

    private Converter() {
    }

    public static Result compress(ImageReader in, FileChannel out, int blockSize, CsoEncoder.Level level,
                                  int threads, AtomicBoolean cancel, final Listener l) throws IOException {
        CsoEncoder.Result r = new CsoEncoder(blockSize, level, threads).encode(in, out, cancel,
                new CsoEncoder.Progress() {
                    @Override
                    public void onProgress(long read, long total, long written) {
                        if (l != null) l.onProgress(Phase.CONVERTING, read, total, written);
                    }
                });
        return new Result(r.inputBytes, r.outputBytes, r.millis, r.crc);
    }

    /** Writes the uncompressed image to {@code out}. */
    public static Result decompress(ImageReader in, FileChannel out, AtomicBoolean cancel, Listener l)
            throws IOException {
        long start = System.currentTimeMillis();
        long total = in.size();
        CRC32 crc = new CRC32();
        byte[] buf = new byte[2 * 1024 * 1024];
        out.truncate(0);
        out.position(0);
        for (long pos = 0; pos < total; ) {
            if (cancel.get()) throw new InterruptedIOException("Cancelled");
            int n = (int) Math.min(buf.length, total - pos);
            in.read(pos, buf, 0, n);
            crc.update(buf, 0, n);
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            while (bb.hasRemaining()) out.write(bb);
            pos += n;
            if (l != null) l.onProgress(Phase.CONVERTING, pos, total, pos);
        }
        out.force(false);
        return new Result(total, total, System.currentTimeMillis() - start, crc.getValue());
    }

    /** Re-reads a written image and checks it decodes to exactly the source data. */
    public static void verify(ImageReader written, long expectedSize, long expectedCrc, AtomicBoolean cancel,
                              final Listener l) throws IOException {
        if (written.size() != expectedSize) {
            throw new IOException("Verification failed: size mismatch");
        }
        long crc = CsoEncoder.crcOf(written, cancel, new CsoEncoder.Progress() {
            @Override
            public void onProgress(long done, long total, long ignored) {
                if (l != null) l.onProgress(Phase.VERIFYING, done, total, 0);
            }
        });
        if (crc != expectedCrc) throw new IOException("Verification failed: data mismatch");
    }
}
