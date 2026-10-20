package com.iso2cso.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Title, disc ID and icon read from a PSP disc image's ISO9660 file system. */
public final class GameInfo {

    private static final int SECTOR = 2048;
    private static final int MAX_ICON_BYTES = 256 * 1024;

    /** True when the image has an ISO9660 volume (any disc), false for unknown data. */
    public boolean iso9660;
    /** True when PSP_GAME/PARAM.SFO exists. */
    public boolean pspGame;
    public String title;
    public String discId;
    public String discVersion;
    public byte[] icon;

    /** "Title [ULUS12345]" style name for output files, or null when unknown. */
    public String suggestedName() {
        if (title == null || title.isEmpty()) return null;
        String name = title;
        if (discId != null && !discId.isEmpty()) name += " [" + discId + "]";
        return sanitizeFileName(name);
    }

    static String sanitizeFileName(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) c = ' ';
            if (c == '\n' || c == '\r') c = ' ';
            b.append(c);
        }
        String r = b.toString().replaceAll("\\s+", " ").trim();
        while (r.endsWith(".")) r = r.substring(0, r.length() - 1);
        return r.length() > 120 ? r.substring(0, 120).trim() : r;
    }

    /** Best effort: never throws, returns what it could find. */
    public static GameInfo read(ImageReader img) {
        GameInfo info = new GameInfo();
        try {
            readInto(img, info);
        } catch (IOException | RuntimeException ignored) {
            // corrupt or non-ISO image; return whatever was found
        }
        return info;
    }

    private static void readInto(ImageReader img, GameInfo info) throws IOException {
        if (img.size() < 17L * SECTOR) return;
        byte[] pvd = new byte[SECTOR];
        img.read(16L * SECTOR, pvd, 0, SECTOR);
        if (pvd[0] != 1 || !"CD001".equals(new String(pvd, 1, 5, StandardCharsets.US_ASCII))) return;
        info.iso9660 = true;
        Entry root = parseRecord(pvd, 156);
        if (root == null) return;
        Entry pspGame = find(img, root, "PSP_GAME");
        if (pspGame == null || !pspGame.dir) return;
        Entry sfo = find(img, pspGame, "PARAM.SFO");
        if (sfo != null && sfo.size > 0 && sfo.size < 64 * 1024) {
            byte[] data = readEntry(img, sfo);
            info.pspGame = true;
            parseSfo(data, info);
        }
        Entry icon = find(img, pspGame, "ICON0.PNG");
        if (icon != null && icon.size > 8 && icon.size <= MAX_ICON_BYTES) {
            info.icon = readEntry(img, icon);
        }
    }

    private static final class Entry {
        long lba;
        int size;
        boolean dir;
        String name;
    }

    private static Entry parseRecord(byte[] b, int off) {
        int len = b[off] & 0xFF;
        if (len < 34 || off + len > b.length) return null;
        Entry e = new Entry();
        e.lba = u32(b, off + 2);
        e.size = (int) Math.min(u32(b, off + 10), Integer.MAX_VALUE);
        e.dir = (b[off + 25] & 2) != 0;
        int nameLen = b[off + 32] & 0xFF;
        String name = new String(b, off + 33, Math.min(nameLen, len - 33), StandardCharsets.US_ASCII);
        int semi = name.indexOf(';');
        if (semi >= 0) name = name.substring(0, semi);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        e.name = name;
        return e;
    }

    private static Entry find(ImageReader img, Entry dir, String name) throws IOException {
        if (!dir.dir || dir.size <= 0 || dir.size > 1024 * 1024) return null;
        long pos = dir.lba * SECTOR;
        if (pos + dir.size > img.size()) return null;
        byte[] data = new byte[dir.size];
        img.read(pos, data, 0, dir.size);
        int off = 0;
        while (off < data.length) {
            int len = data[off] & 0xFF;
            if (len == 0) {
                off = (off / SECTOR + 1) * SECTOR; // records never cross sectors
                continue;
            }
            Entry e = parseRecord(data, off);
            if (e != null && e.name.equalsIgnoreCase(name)) return e;
            off += len;
        }
        return null;
    }

    private static byte[] readEntry(ImageReader img, Entry e) throws IOException {
        byte[] data = new byte[e.size];
        img.read(e.lba * SECTOR, data, 0, e.size);
        return data;
    }

    static void parseSfo(byte[] d, GameInfo info) {
        if (d.length < 20 || d[0] != 0 || d[1] != 'P' || d[2] != 'S' || d[3] != 'F') return;
        int keyTable = (int) u32(d, 8), dataTable = (int) u32(d, 12), count = (int) u32(d, 16);
        for (int i = 0; i < count && 20 + i * 16 + 16 <= d.length; i++) {
            int e = 20 + i * 16;
            int keyOff = u16(d, e), fmt = u16(d, e + 2);
            int len = (int) u32(d, e + 4), dataOff = (int) u32(d, e + 12);
            String key = cString(d, keyTable + keyOff, 64);
            int start = dataTable + dataOff;
            if (fmt != 0x0204 || start < 0 || start + len > d.length || len <= 0) continue;
            String value = new String(d, start, len, StandardCharsets.UTF_8);
            int nul = value.indexOf('\0');
            if (nul >= 0) value = value.substring(0, nul);
            value = value.trim();
            if (key.equals("TITLE")) info.title = value;
            else if (key.equals("DISC_ID")) info.discId = value.toUpperCase(Locale.ROOT);
            else if (key.equals("DISC_VERSION")) info.discVersion = value;
        }
    }

    private static String cString(byte[] d, int off, int max) {
        if (off < 0 || off >= d.length) return "";
        int end = off;
        while (end < d.length && end - off < max && d[end] != 0) end++;
        return new String(d, off, end - off, StandardCharsets.US_ASCII);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }
}
