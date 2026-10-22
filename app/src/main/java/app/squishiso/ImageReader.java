package app.squishiso;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Random access to the uncompressed bytes of a disc image, whether it's stored as ISO or CSO. */
public abstract class ImageReader implements Closeable {

    /** Uncompressed image size in bytes. */
    public abstract long size();

    /** Reads exactly {@code len} bytes at {@code pos}; reading past the end is an error. */
    public abstract void read(long pos, byte[] buf, int off, int len) throws IOException;

    public boolean isCompressed() {
        return false;
    }

    @Override
    public void close() throws IOException {
    }

    /** Opens {@code ch}, detecting CSO by its magic and treating anything else as a raw ISO. */
    public static ImageReader open(FileChannel ch) throws IOException {
        ByteBuffer magic = le(4);
        if (ch.size() >= 24) readFully(ch, magic, 0);
        magic.flip();
        if (magic.remaining() == 4 && magic.getInt(0) == 0x4F534943) { // "CISO" little-endian
            return new CsoImageReader(ch);
        }
        return new IsoImageReader(ch);
    }

    static void readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("Unexpected end of file");
            pos += n;
        }
    }

    static ByteBuffer le(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A plain ISO file. */
    static final class IsoImageReader extends ImageReader {
        private final FileChannel ch;
        private final long size;

        IsoImageReader(FileChannel ch) throws IOException {
            this.ch = ch;
            this.size = ch.size();
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos < 0 || pos + len > size) throw new IOException("Read past end of image");
            readFully(ch, ByteBuffer.wrap(buf, off, len), pos);
        }
    }
}
