package com.iso2cso.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;
import org.junit.Test;

public class CsoEncoderTest {

    @Test
    public void roundTripPspBlocks() throws Exception {
        roundTrip(CsoEncoder.BLOCK_PSP, false, 5_000_000 + 2048 * 3);
    }

    @Test
    public void roundTripLargeBlocksWithPartialTail() throws Exception {
        roundTrip(CsoEncoder.BLOCK_PPSSPP, true, 3_000_017);
    }

    @Test
    public void alignmentStaysZeroForUmdSizedImages() {
        CsoEncoder enc = new CsoEncoder(2048, false, 1);
        assertEquals(0, enc.computeAlign((int) (1_900_000_000L / 2048)));
        assertTrue(enc.computeAlign((int) (4_500_000_000L / 2048)) > 0);
    }

    private static void roundTrip(int blockSize, boolean exhaustive, int size) throws Exception {
        byte[] iso = sampleImage(size);
        File in = File.createTempFile("input", ".iso");
        File out = File.createTempFile("output", ".cso");
        try {
            Files.write(in.toPath(), iso);
            try (FileChannel ic = FileChannel.open(in.toPath(), StandardOpenOption.READ);
                 FileChannel oc = FileChannel.open(out.toPath(), StandardOpenOption.READ,
                         StandardOpenOption.WRITE)) {
                CsoEncoder.Result r = new CsoEncoder(blockSize, exhaustive, 4)
                        .encode(ic, oc, new AtomicBoolean(), null);
                assertEquals(size, r.inputBytes);
                assertEquals(out.length(), r.outputBytes);
            }
            byte[] cso = Files.readAllBytes(out.toPath());
            assertTrue("should compress", cso.length < iso.length);
            assertArrayEquals(iso, decode(cso));
        } finally {
            in.delete();
            out.delete();
        }
    }

    /** Mix of zeros, text-like data and incompressible noise, like a real disc. */
    private static byte[] sampleImage(int size) {
        byte[] b = new byte[size];
        Random rnd = new Random(42);
        for (int off = 0; off < size; off += 65536) {
            int end = Math.min(size, off + 65536);
            int kind = (off / 65536) % 3;
            for (int i = off; i < end; i++) {
                if (kind == 1) b[i] = (byte) ("PSP_GAME/USRDIR/".charAt(i % 16) + (i / 4096 % 3));
                else if (kind == 2) b[i] = (byte) rnd.nextInt();
            }
        }
        return b;
    }

    private static byte[] decode(byte[] cso) throws Exception {
        ByteBuffer h = ByteBuffer.wrap(cso).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x4F534943, h.getInt(0)); // "CISO"
        long total = h.getLong(8);
        int blockSize = h.getInt(16);
        int align = cso[21];
        int blocks = (int) ((total + blockSize - 1) / blockSize);
        byte[] outBuf = new byte[blocks * blockSize];
        for (int i = 0; i < blocks; i++) {
            int e0 = h.getInt(24 + 4 * i);
            int e1 = h.getInt(24 + 4 * (i + 1));
            long start = (long) (e0 & 0x7FFFFFFF) << align;
            long end = (long) (e1 & 0x7FFFFFFF) << align;
            if (e0 < 0) {
                System.arraycopy(cso, (int) start, outBuf, i * blockSize, blockSize);
            } else {
                Inflater inf = new Inflater(true);
                inf.setInput(cso, (int) start, (int) (end - start));
                assertEquals(blockSize, inf.inflate(outBuf, i * blockSize, blockSize));
                inf.end();
            }
        }
        byte[] result = new byte[(int) total];
        System.arraycopy(outBuf, 0, result, 0, (int) total);
        return result;
    }
}
