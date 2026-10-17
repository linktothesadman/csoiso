package com.iso2cso.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service that converts the queued ISOs so work survives leaving the app. */
public class ConvertService extends Service {

    public static final String ACTION_START = "com.iso2cso.app.START";
    public static final String ACTION_CANCEL = "com.iso2cso.app.CANCEL";
    public static final String EXTRA_INPUTS = "inputs";
    public static final String EXTRA_TREE = "tree";
    public static final String EXTRA_BLOCK_SIZE = "blockSize";
    public static final String EXTRA_EXHAUSTIVE = "exhaustive";
    public static final String EXTRA_DELETE_ORIGINAL = "deleteOriginal";

    private static final String CHANNEL = "convert";
    private static final int NOTIFY_PROGRESS = 1;
    private static final int NOTIFY_DONE = 2;

    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation") // typed getParcelable*Extra overloads need API 33
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel.set(true);
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || worker != null) return START_NOT_STICKY;

        createChannel();
        Notification n = progressNotification("Starting…", 0, 0);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFY_PROGRESS, n);
        }

        final ArrayList<Uri> inputs = intent.getParcelableArrayListExtra(EXTRA_INPUTS);
        final Uri tree = intent.getParcelableExtra(EXTRA_TREE);
        final int blockSize = intent.getIntExtra(EXTRA_BLOCK_SIZE, CsoEncoder.BLOCK_PSP);
        final boolean exhaustive = intent.getBooleanExtra(EXTRA_EXHAUSTIVE, true);
        final boolean deleteOriginal = intent.getBooleanExtra(EXTRA_DELETE_ORIGINAL, false);
        cancel.set(false);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runJobs(inputs, tree, blockSize, exhaustive, deleteOriginal);
            }
        }, "cso-convert");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runJobs(final ArrayList<Uri> inputs, Uri tree, int blockSize, boolean exhaustive,
                         boolean deleteOriginal) {
        final ConvertState st = ConvertState.INSTANCE;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iso2cso:convert");
        wl.acquire(6 * 60 * 60 * 1000L);
        st.update(new Runnable() {
            @Override
            public void run() {
                st.running = true;
                st.fileCount = inputs.size();
                st.totalIn = 0;
                st.totalOut = 0;
                st.log.setLength(0);
            }
        });

        ContentResolver cr = getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        int threads = Runtime.getRuntime().availableProcessors();
        CsoEncoder encoder = new CsoEncoder(blockSize, exhaustive, threads);
        int ok = 0;
        long savedIn = 0, savedOut = 0;

        for (int i = 0; i < inputs.size() && !cancel.get(); i++) {
            final Uri in = inputs.get(i);
            final String name = displayName(cr, in);
            final int index = i;
            st.update(new Runnable() {
                @Override
                public void run() {
                    st.fileIndex = index;
                    st.currentName = name;
                    st.bytesRead = 0;
                    st.bytesWritten = 0;
                    st.bytesTotal = 0;
                }
            });

            Uri out = null;
            try {
                out = DocumentsContract.createDocument(cr, parent, "application/octet-stream",
                        csoName(name));
                if (out == null) throw new java.io.IOException("Could not create output file");
                CsoEncoder.Result r;
                try (ParcelFileDescriptor ipfd = cr.openFileDescriptor(in, "r");
                     ParcelFileDescriptor opfd = cr.openFileDescriptor(out, "rw");
                     FileChannel ic = new FileInputStream(ipfd.getFileDescriptor()).getChannel();
                     FileChannel oc = new FileOutputStream(opfd.getFileDescriptor()).getChannel()) {
                    r = encoder.encode(ic, oc, cancel, new ProgressSink(name, index, inputs.size()));
                }
                ok++;
                savedIn += r.inputBytes;
                savedOut += r.outputBytes;
                final String line = String.format(Locale.US, "✓ %s: %s → %s (%.1f%%, %.1fs)",
                        name, size(r.inputBytes), size(r.outputBytes),
                        100.0 * r.outputBytes / r.inputBytes, r.millis / 1000.0);
                final long ri = r.inputBytes, ro = r.outputBytes;
                String deleted = "";
                if (deleteOriginal) {
                    try {
                        deleted = DocumentsContract.deleteDocument(cr, in)
                                ? "  (original deleted)" : "  (could not delete original)";
                    } catch (Exception e) {
                        deleted = "  (could not delete original: " + e.getMessage() + ")";
                    }
                }
                final String full = line + deleted;
                st.update(new Runnable() {
                    @Override
                    public void run() {
                        st.totalIn += ri;
                        st.totalOut += ro;
                        st.log(full);
                    }
                });
            } catch (Exception e) {
                deleteQuietly(cr, out);
                final String msg = (e instanceof InterruptedIOException || cancel.get())
                        ? "✗ " + name + ": cancelled"
                        : "✗ " + name + ": " + e.getMessage();
                st.update(new Runnable() {
                    @Override
                    public void run() {
                        st.log(msg);
                    }
                });
            }
        }

        final String summary = cancel.get() && ok < inputs.size()
                ? "Cancelled after " + ok + " file(s)"
                : String.format(Locale.US, "Done: %d/%d converted, saved %s", ok, inputs.size(),
                        size(Math.max(0, savedIn - savedOut)));
        st.update(new Runnable() {
            @Override
            public void run() {
                st.running = false;
                st.log(summary);
            }
        });
        wl.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFY_DONE, baseBuilder().setContentTitle(summary).setAutoCancel(true).build());
        worker = null;
        stopSelf();
    }

    /** Throttles progress to the UI and notification. */
    private final class ProgressSink implements CsoEncoder.Progress {
        private final String name;
        private final int index;
        private final int count;
        private long lastUi;
        private long lastNotify;

        ProgressSink(String name, int index, int count) {
            this.name = name;
            this.index = index;
            this.count = count;
        }

        @Override
        public void onProgress(final long read, final long total, final long written) {
            long now = System.currentTimeMillis();
            if (now - lastUi >= 150 || read == total) {
                lastUi = now;
                final ConvertState st = ConvertState.INSTANCE;
                st.update(new Runnable() {
                    @Override
                    public void run() {
                        st.bytesRead = read;
                        st.bytesTotal = total;
                        st.bytesWritten = written;
                    }
                });
            }
            if (now - lastNotify >= 1000) {
                lastNotify = now;
                String title = (count > 1 ? "(" + (index + 1) + "/" + count + ") " : "") + name;
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFY_PROGRESS, progressNotification(title, read, total));
            }
        }
    }

    private Notification.Builder baseBuilder() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi);
    }

    private Notification progressNotification(String title, long read, long total) {
        PendingIntent cancelPi = PendingIntent.getService(this, 1,
                new Intent(this, ConvertService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int pct = total > 0 ? (int) (read * 100 / total) : 0;
        Notification.Builder b = baseBuilder()
                .setContentTitle(title)
                .setContentText(total > 0 ? pct + "%" : "Preparing…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, total <= 0)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancelPi).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Conversion",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String size(long bytes) {
        return Formatter.formatFileSize(this, bytes);
    }

    static String csoName(String isoName) {
        String base = isoName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + ".cso";
    }

    static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
            // fall through to the URI's last segment
        }
        String seg = uri.getLastPathSegment();
        return seg != null ? seg.substring(seg.lastIndexOf('/') + 1) : "game.iso";
    }

    private static void deleteQuietly(ContentResolver cr, Uri uri) {
        if (uri == null) return;
        try {
            DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignored) {
            // best effort cleanup of a partial file
        }
    }
}
