package com.iso2cso.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads CSO (CISO v1, and v2 deflate blocks) images. Used to decompress CSOs back to ISO, to
 * re-compress existing CSOs, and to verify freshly written files.
 */
final class CsoImageReader extends ImageReader {

    private static final int PLAIN_FLAG = 0x80000000;

    private final FileChannel ch;
    private final long total;
    private final int blockSize;
    private final int align;
    private final int version;
    private final int[] index;
    private final Inflater inflater = new Inflater(true);
    private final byte[] block;
    private byte[] packed;
    private int cachedBlock = -1;

    CsoImageReader(FileChannel ch) throws IOException {
        this.ch = ch;
        ByteBuffer h = le(24);
        readFully(ch, h, 0);
        h.flip();
        if (h.getInt(0) != 0x4F534943) throw new IOException("Not a CSO file");
        int headerSize = h.getInt(4);
        total = h.getLong(8);
        blockSize = h.getInt(16);
        version = h.get(20) & 0xFF;
        align = h.get(21) & 0xFF;
        if (version > 2) throw new IOException("Unsupported CSO version " + version);
        if (blockSize < 512 || blockSize > (1 << 24) || Integer.bitCount(blockSize) != 1)
            throw new IOException("Invalid CSO block size " + blockSize);
        if (total <= 0 || align > 31) throw new IOException("Invalid CSO header");
        long blocksL = (total + blockSize - 1) / blockSize;
        if (blocksL > Integer.MAX_VALUE - 1) throw new IOException("Invalid CSO size");
        int blocks = (int) blocksL;
        long indexStart = version >= 2 ? Math.max(24, headerSize) : 24;
        ByteBuffer ib = le(4 * (blocks + 1));
        readFully(ch, ib, indexStart);
        ib.flip();
        index = new int[blocks + 1];
        IntBuffer ints = ib.asIntBuffer();
        ints.get(index);
        block = new byte[blockSize];
        packed = new byte[blockSize + 1024];
    }

    @Override
    public long size() {
        return total;
    }

    @Override
    public boolean isCompressed() {
        return true;
    }

    int blockSize() {
        return blockSize;
    }

    @Override
    public void read(long pos, byte[] buf, int off, int len) throws IOException {
        if (pos < 0 || pos + len > total) throw new IOException("Read past end of image");
        while (len > 0) {
            int b = (int) (pos / blockSize);
            int inBlock = (int) (pos % blockSize);
            loadBlock(b);
            int n = Math.min(len, blockSize - inBlock);
            System.arraycopy(block, inBlock, buf, off, n);
            pos += n;
            off += n;
            len -= n;
        }
    }

    private void loadBlock(int b) throws IOException {
        if (b == cachedBlock) return;
        long start = (long) (index[b] & ~PLAIN_FLAG) << align;
        long end = (long) (index[b + 1] & ~PLAIN_FLAG) << align;
        boolean plain = (index[b] & PLAIN_FLAG) != 0;
        long stored = end - start;
        if (stored < 0 || stored > blockSize + 65536) throw new IOException("Corrupt CSO index at block " + b);
        // Uncompressed blocks may be followed by alignment padding; read only what fits a block.
        int want = (int) (plain ? Math.min(stored, blockSize) : stored);
        if (plain || stored >= blockSize && version < 2) {
            // v1 readers also treat a block whose stored size is a full block as uncompressed.
            if (want < blockSize && b != index.length - 2) throw new IOException("Short block " + b);
            java.util.Arrays.fill(block, (byte) 0);
            readFully(ch, ByteBuffer.wrap(block, 0, Math.min(want, blockSize)), start);
        } else {
            if (packed.length < want) packed = new byte[want];
            readFully(ch, ByteBuffer.wrap(packed, 0, want), start);
            inflater.reset();
            inflater.setInput(packed, 0, want);
            try {
                int n = 0;
                while (n < blockSize && !inflater.finished()) {
                    int r = inflater.inflate(block, n, blockSize - n);
                    if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    n += r;
                }
                if (n != blockSize) throw new IOException("Corrupt compressed block " + b);
            } catch (DataFormatException e) {
                throw new IOException("Corrupt compressed block " + b);
            }
        }
        cachedBlock = b;
    }

    @Override
    public void close() {
        inflater.end();
    }
}
