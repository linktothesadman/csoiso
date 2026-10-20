package com.iso2cso.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/** User settings for a conversion run, persisted in SharedPreferences and passed to the service. */
public final class ConvertOptions {

    public enum Mode { COMPRESS, DECOMPRESS }

    public enum Naming { FILE_NAME, GAME_TITLE }

    public enum Existing { SKIP, REPLACE, KEEP_BOTH }

    public Mode mode = Mode.COMPRESS;
    public boolean pspCompatible = true; // 2 KB blocks; false = 16 KB (PPSSPP only)
    public CsoEncoder.Level level = CsoEncoder.Level.MAX;
    public Naming naming = Naming.FILE_NAME;
    public Existing existing = Existing.SKIP;
    public boolean verify = true;
    public boolean deleteOriginal;
    public Uri outputTree;

    public int blockSize() {
        return pspCompatible ? CsoEncoder.BLOCK_PSP : CsoEncoder.BLOCK_PPSSPP;
    }

    public static ConvertOptions load(SharedPreferences p) {
        ConvertOptions o = new ConvertOptions();
        o.mode = enumOr(Mode.class, p.getString("mode", null), Mode.COMPRESS);
        o.pspCompatible = p.getBoolean("pspCompatible", true);
        o.level = enumOr(CsoEncoder.Level.class, p.getString("level", null), CsoEncoder.Level.MAX);
        o.naming = enumOr(Naming.class, p.getString("naming", null), Naming.FILE_NAME);
        o.existing = enumOr(Existing.class, p.getString("existing", null), Existing.SKIP);
        o.verify = p.getBoolean("verify", true);
        o.deleteOriginal = p.getBoolean("deleteOriginal", false);
        String tree = p.getString("outputTree", null);
        o.outputTree = tree != null ? Uri.parse(tree) : null;
        return o;
    }

    public void save(SharedPreferences p) {
        p.edit()
                .putString("mode", mode.name())
                .putBoolean("pspCompatible", pspCompatible)
                .putString("level", level.name())
                .putString("naming", naming.name())
                .putString("existing", existing.name())
                .putBoolean("verify", verify)
                .putBoolean("deleteOriginal", deleteOriginal)
                .putString("outputTree", outputTree != null ? outputTree.toString() : null)
                .apply();
    }

    public void toIntent(Intent i) {
        i.putExtra("mode", mode.name())
                .putExtra("pspCompatible", pspCompatible)
                .putExtra("level", level.name())
                .putExtra("naming", naming.name())
                .putExtra("existing", existing.name())
                .putExtra("verify", verify)
                .putExtra("deleteOriginal", deleteOriginal)
                .putExtra("outputTree", outputTree != null ? outputTree.toString() : null);
    }

    public static ConvertOptions fromIntent(Intent i) {
        ConvertOptions o = new ConvertOptions();
        o.mode = enumOr(Mode.class, i.getStringExtra("mode"), Mode.COMPRESS);
        o.pspCompatible = i.getBooleanExtra("pspCompatible", true);
        o.level = enumOr(CsoEncoder.Level.class, i.getStringExtra("level"), CsoEncoder.Level.MAX);
        o.naming = enumOr(Naming.class, i.getStringExtra("naming"), Naming.FILE_NAME);
        o.existing = enumOr(Existing.class, i.getStringExtra("existing"), Existing.SKIP);
        o.verify = i.getBooleanExtra("verify", true);
        o.deleteOriginal = i.getBooleanExtra("deleteOriginal", false);
        String tree = i.getStringExtra("outputTree");
        o.outputTree = tree != null ? Uri.parse(tree) : null;
        return o;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
