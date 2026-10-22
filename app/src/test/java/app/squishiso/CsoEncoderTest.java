package app.squishiso;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import org.junit.After;
import org.junit.Test;

public class CsoEncoderTest {

    private final List<File> temps = new ArrayList<>();

    @After
    public void cleanup() {
        for (File f : temps) f.delete();
    }

    @Test
    public void roundTripPspBlocksFast() throws Exception {
        byte[] iso = sampleImage(3_000_000 + 2048 * 3);
        byte[] cso = compress(iso, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.FAST);
        assertTrue("should compress", cso.length < iso.length);
        assertArrayEquals(iso, independentDecode(cso));
    }

    @Test
    public void roundTripLargeBlocksWithPartialTail() throws Exception {
        byte[] iso = sampleImage(1_000_017);
        byte[] cso = compress(iso, CsoEncoder.BLOCK_PPSSPP, CsoEncoder.Level.MAX);
        assertArrayEquals(iso, independentDecode(cso));
    }

    @Test
    public void maxLevelIsSmallerAndStillStandardDeflate() throws Exception {
        byte[] iso = sampleImage(600_000);
        byte[] fast = compress(iso, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.FAST);
        byte[] max = compress(iso, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.MAX);
        assertTrue("max " + max.length + " vs fast " + fast.length, max.length < fast.length);
        assertArrayEquals(iso, independentDecode(max));
    }

    @Test
    public void pspCompatibleLayout() throws Exception {
        byte[] iso = sampleImage(400_000);
        byte[] cso = compress(iso, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.MAX);
        ByteBuffer h = ByteBuffer.wrap(cso).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x18, h.getInt(4));     // header size
        assertEquals(2048, h.getInt(16));    // block size the PSP requires
        assertEquals(1, cso[20]);            // version 1
        assertEquals(0, cso[21]);            // no alignment for UMD-sized images
        int blocks = (iso.length + 2047) / 2048;
        for (int i = 0; i < blocks; i++) {
            int e0 = h.getInt(24 + 4 * i), e1 = h.getInt(28 + 4 * i);
            int stored = (e1 & 0x7FFFFFFF) - (e0 & 0x7FFFFFFF);
            if (e0 < 0) assertEquals(2048, stored);          // plain blocks are exactly one block
            else assertTrue(stored > 0 && stored < 2048);    // compressed blocks are always smaller
        }
    }

    @Test
    public void csoReaderDecompressesAndRecompresses() throws Exception {
        byte[] iso = sampleImage(900_000);
        File big = write(compress(iso, CsoEncoder.BLOCK_PPSSPP, CsoEncoder.Level.FAST));

        // CSO -> ISO
        File outIso = temp(".iso");
        try (FileChannel ic = open(big); ImageReader r = ImageReader.open(ic);
             FileChannel oc = openRw(outIso)) {
            assertTrue(r.isCompressed());
            Converter.Result res = Converter.decompress(r, oc, new AtomicBoolean(), null);
            assertEquals(iso.length, res.outputBytes);
        }
        assertArrayEquals(iso, Files.readAllBytes(outIso.toPath()));

        // CSO (16K) -> CSO (2K), keeping the data identical
        File small = temp(".cso");
        Converter.Result res;
        try (FileChannel ic = open(big); ImageReader r = ImageReader.open(ic); FileChannel oc = openRw(small)) {
            res = Converter.compress(r, oc, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.MAX, 4, new AtomicBoolean(), null);
        }
        assertEquals(crc(iso), res.crc);
        assertArrayEquals(iso, independentDecode(Files.readAllBytes(small.toPath())));
        try (FileChannel ch = open(small); ImageReader r = ImageReader.open(ch)) {
            Converter.verify(r, iso.length, res.crc, new AtomicBoolean(), null);
        }
    }

    @Test
    public void verifyCatchesCorruption() throws Exception {
        byte[] iso = sampleImage(300_000);
        byte[] cso = compress(iso, CsoEncoder.BLOCK_PSP, CsoEncoder.Level.FAST);
        ByteBuffer h = ByteBuffer.wrap(cso).order(ByteOrder.LITTLE_ENDIAN);
        // Corrupt the middle of the data area, then verify against the original CRC.
        int dataStart = h.getInt(24) & 0x7FFFFFFF;
        cso[dataStart + (cso.length - dataStart) / 2] ^= 0x5A;
        File f = write(cso);
        try (FileChannel ch = open(f); ImageReader r = ImageReader.open(ch)) {
            Converter.verify(r, iso.length, crc(iso), new AtomicBoolean(), null);
            fail("corruption not detected");
        } catch (IOException expected) {
            // corrupt deflate data or CRC mismatch
        }
    }

    @Test
    public void cancelStopsEncoding() throws Exception {
        byte[] iso = sampleImage(2_000_000);
        File in = write(iso), out = temp(".cso");
        final AtomicBoolean cancel = new AtomicBoolean();
        try (FileChannel ic = open(in); ImageReader r = ImageReader.open(ic); FileChannel oc = openRw(out)) {
            new CsoEncoder(2048, CsoEncoder.Level.FAST, 2).encode(r, oc, cancel, new CsoEncoder.Progress() {
                @Override
                public void onProgress(long read, long total, long written) {
                    if (read > total / 4) cancel.set(true);
                }
            });
            fail("expected cancellation");
        } catch (java.io.InterruptedIOException expected) {
            // ok
        }
    }

    @Test
    public void alignmentStaysZeroForUmdSizedImages() {
        CsoEncoder enc = new CsoEncoder(2048, CsoEncoder.Level.FAST, 1);
        assertEquals(0, enc.computeAlign((int) (1_900_000_000L / 2048)));
        assertTrue(enc.computeAlign((int) (4_500_000_000L / 2048)) > 0);
    }

    @Test
    public void readsGameInfoFromIsoAndCso() throws Exception {
        byte[] icon = new byte[300];
        icon[0] = (byte) 0x89;
        icon[1] = 'P';
        byte[] iso = TinyIso.build(sfo("Test Racer: \"Most\" Wanted?", "ULUS10036", "1.00"), icon);

        File isoFile = write(iso);
        try (FileChannel ch = open(isoFile); ImageReader r = ImageReader.open(ch)) {
            GameInfo g = GameInfo.read(r);
            assertTrue(g.iso9660 && g.pspGame);
            assertEquals("Test Racer: \"Most\" Wanted?", g.title);
            assertEquals("ULUS10036", g.discId);
            assertEquals("1.00", g.discVersion);
            assertArrayEquals(icon, g.icon);
            assertEquals("Test Racer Most Wanted [ULUS10036]", g.suggestedName());
        }
        File csoFile = write(compress(iso, 2048, CsoEncoder.Level.FAST));
        try (FileChannel ch = open(csoFile); ImageReader r = ImageReader.open(ch)) {
            assertEquals("ULUS10036", GameInfo.read(r).discId);
        }
    }

    @Test
    public void gameInfoToleratesNonIsoData() throws Exception {
        File f = write(sampleImage(100_000));
        try (FileChannel ch = open(f); ImageReader r = ImageReader.open(ch)) {
            GameInfo g = GameInfo.read(r);
            assertFalse(g.iso9660);
            assertNull(g.title);
            assertNull(g.suggestedName());
        }
    }

    // ---------- helpers ----------

    private byte[] compress(byte[] iso, int blockSize, CsoEncoder.Level level) throws Exception {
        File in = write(iso), out = temp(".cso");
        try (FileChannel ic = open(in); ImageReader r = ImageReader.open(ic); FileChannel oc = openRw(out)) {
            CsoEncoder.Result res = new CsoEncoder(blockSize, level, 4).encode(r, oc, new AtomicBoolean(), null);
            assertEquals(iso.length, res.inputBytes);
            assertEquals(out.length(), res.outputBytes);
            assertEquals(crc(iso), res.crc);
        }
        return Files.readAllBytes(out.toPath());
    }

    private File temp(String suffix) throws IOException {
        File f = File.createTempFile("csotest", suffix);
        temps.add(f);
        return f;
    }

    private File write(byte[] data) throws IOException {
        File f = temp(".bin");
        Files.write(f.toPath(), data);
        return f;
    }

    private static FileChannel open(File f) throws IOException {
        return FileChannel.open(f.toPath(), StandardOpenOption.READ);
    }

    private static FileChannel openRw(File f) throws IOException {
        return FileChannel.open(f.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    /** Mix of zeros, text-like data and incompressible noise, like a real disc. */
    static byte[] sampleImage(int size) {
        byte[] b = new byte[size];
        Random rnd = new Random(42);
        for (int off = 0; off < size; off += 65536) {
            int end = Math.min(size, off + 65536);
            int kind = (off / 65536) % 3;
            for (int i = off; i < end; i++) {
                if (kind == 1) b[i] = (byte) ("PSP_GAME/USRDIR/".charAt(i % 16) + (i / 4096 % 3) + rnd.nextInt(2));
                else if (kind == 2) b[i] = (byte) rnd.nextInt();
            }
        }
        return b;
    }

    /** Minimal CSO decoder written straight from the format description, independent of the app's reader. */
    private static byte[] independentDecode(byte[] cso) throws Exception {
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

    private static byte[] sfo(String title, String discId, String version) {
        String[][] kv = {{"DISC_ID", discId}, {"DISC_VERSION", version}, {"TITLE", title}};
        ByteBuffer keys = ByteBuffer.allocate(256), data = ByteBuffer.allocate(512);
        ByteBuffer entries = ByteBuffer.allocate(16 * kv.length).order(ByteOrder.LITTLE_ENDIAN);
        for (String[] e : kv) {
            byte[] v = (e[1] + "\0").getBytes(StandardCharsets.UTF_8);
            int max = (v.length + 3) & ~3;
            entries.putShort((short) keys.position()).putShort((short) 0x0204)
                    .putInt(v.length).putInt(max).putInt(data.position());
            keys.put((e[0] + "\0").getBytes(StandardCharsets.US_ASCII));
            data.put(v).position(data.position() + max - v.length);
        }
        int keyStart = 20 + entries.capacity();
        int dataStart = (keyStart + keys.position() + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(dataStart + data.position()).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {0, 'P', 'S', 'F'}).putInt(0x101).putInt(keyStart).putInt(dataStart).putInt(kv.length);
        out.put(entries.array());
        out.put(keys.array(), 0, keys.position());
        out.position(dataStart);
        out.put(data.array(), 0, data.position());
        return out.array();
    }

    /** Builds a tiny ISO9660 image: /PSP_GAME/PARAM.SFO and /PSP_GAME/ICON0.PNG. */
    static final class TinyIso {
        static byte[] build(byte[] sfo, byte[] icon) {
            int root = 18, game = 19, sfoLba = 20, iconLba = 21;
            byte[] img = new byte[2048 * 40];
            ByteBuffer b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN);
            int pvd = 16 * 2048;
            img[pvd] = 1;
            System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, img, pvd + 1, 5);
            img[pvd + 6] = 1;
            record(b, pvd + 156, root, 2048, true, "\0");
            img[17 * 2048] = (byte) 255; // volume descriptor set terminator
            int off = record(b, root * 2048, root, 2048, true, "\0");
            off = record(b, off, root, 2048, true, "\1");
            record(b, off, game, 2048, true, "PSP_GAME");
            off = record(b, game * 2048, game, 2048, true, "\0");
            off = record(b, off, root, 2048, true, "\1");
            off = record(b, off, iconLba, icon.length, false, "ICON0.PNG;1");
            record(b, off, sfoLba, sfo.length, false, "PARAM.SFO;1");
            System.arraycopy(sfo, 0, img, sfoLba * 2048, sfo.length);
            System.arraycopy(icon, 0, img, iconLba * 2048, icon.length);
            return img;
        }

        private static int record(ByteBuffer b, int off, int lba, int size, boolean dir, String name) {
            byte[] n = name.getBytes(StandardCharsets.US_ASCII);
            int len = 33 + n.length + ((n.length % 2 == 0) ? 1 : 0);
            b.put(off, (byte) len);
            b.putInt(off + 2, lba);
            b.putInt(off + 10, size);
            b.put(off + 25, (byte) (dir ? 2 : 0));
            b.put(off + 32, (byte) n.length);
            for (int i = 0; i < n.length; i++) b.put(off + 33 + i, n[i]);
            return off + len;
        }
    }
}
