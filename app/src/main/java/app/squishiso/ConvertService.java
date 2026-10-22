package app.squishiso;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service that works through the queue so conversions survive leaving the app. */
public class ConvertService extends Service {

    public static final String ACTION_START = "app.squishiso.START";
    public static final String ACTION_CANCEL = "app.squishiso.CANCEL";

    private static final String CHANNEL_PROGRESS = "convert";
    private static final String CHANNEL_DONE = "done";
    private static final int NOTIFY_PROGRESS = 1;
    private static final int NOTIFY_DONE = 2;
    private static final long MIN_FREE_BYTES = 64L * 1024 * 1024;

    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile Thread worker;

    /** Immutable copy of what the worker needs from a {@link Job}. */
    private static final class Task {
        long id;
        Uri uri;
        String fileName;
        String suggestedName;
        long size;
        boolean isCso;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            requestCancel();
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || worker != null) return START_NOT_STICKY;

        createChannels();
        Notification n = progressNotification("Starting…", "", 0, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFY_PROGRESS, n);
        }

        final ConvertOptions opts = ConvertOptions.fromIntent(intent);
        if (opts.outputTree == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        final List<Task> tasks = new ArrayList<>();
        ConvertState st = ConvertState.INSTANCE;
        long total = 0;
        for (Job j : st.jobs) {
            if (j.isFinished()) continue;
            Task t = new Task();
            t.id = j.id;
            t.uri = j.uri;
            t.fileName = j.fileName;
            t.suggestedName = j.suggestedName;
            t.size = Math.max(0, j.size);
            t.isCso = j.isCso;
            tasks.add(t);
            total += t.size;
            j.status = Job.Status.QUEUED;
            j.message = null;
            j.progress = 0;
        }
        st.running = true;
        st.cancelling = false;
        st.runDone = 0;
        st.runTotal = total;
        st.bytesPerSec = 0;
        st.etaSeconds = -1;
        st.summary = "";
        st.notifyChanged();

        cancel.set(false);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runAll(tasks, opts);
            }
        }, "cso-convert");
        worker.start();
        return START_NOT_STICKY;
    }

    /** Android 15+ limits dataSync services to 6 hours a day; stop cleanly if we hit it. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        requestCancel();
    }

    private void requestCancel() {
        cancel.set(true);
        ConvertState.INSTANCE.update(new Runnable() {
            @Override
            public void run() {
                ConvertState.INSTANCE.cancelling = true;
            }
        });
    }

    private void runAll(List<Task> tasks, ConvertOptions opts) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "squishiso:convert");
        wl.acquire(6 * 60 * 60 * 1000L);
        ContentResolver cr = getContentResolver();
        Map<String, String> existing = Docs.children(cr, opts.outputTree);
        int ok = 0, skipped = 0, failed = 0;
        long savedTotal = 0, doneBefore = 0;

        for (final Task t : tasks) {
            if (cancel.get()) {
                setStatus(t.id, Job.Status.CANCELLED, "Cancelled", -1);
                continue;
            }
            Outcome o = runOne(cr, t, opts, existing, doneBefore, tasks.size());
            doneBefore += t.size;
            if (o.status == Job.Status.DONE) {
                ok++;
                savedTotal += o.saved;
            } else if (o.status == Job.Status.SKIPPED) {
                skipped++;
            } else if (o.status == Job.Status.FAILED) {
                failed++;
            }
            final long done = doneBefore;
            ConvertState.INSTANCE.update(new Runnable() {
                @Override
                public void run() {
                    ConvertState.INSTANCE.runDone = done;
                }
            });
        }

        if (savedTotal > 0) {
            SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
            p.edit().putLong("lifetimeSaved", p.getLong("lifetimeSaved", 0) + savedTotal).apply();
        }
        StringBuilder sb = new StringBuilder();
        if (cancel.get()) sb.append("Cancelled · ");
        sb.append(ok).append(ok == 1 ? " file converted" : " files converted");
        if (skipped > 0) sb.append(" · ").append(skipped).append(" skipped");
        if (failed > 0) sb.append(" · ").append(failed).append(" failed");
        if (savedTotal > 0) sb.append(" · saved ").append(size(savedTotal));
        final String summary = sb.toString();
        ConvertState.INSTANCE.update(new Runnable() {
            @Override
            public void run() {
                ConvertState st = ConvertState.INSTANCE;
                st.running = false;
                st.cancelling = false;
                st.summary = summary;
                st.etaSeconds = -1;
            }
        });
        wl.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFY_DONE, baseBuilder(CHANNEL_DONE)
                .setContentTitle(failed > 0 ? "Finished with errors" : "Conversion finished")
                .setContentText(summary)
                .setAutoCancel(true)
                .build());
        worker = null;
        stopSelf();
    }

    private static final class Outcome {
        Job.Status status;
        long saved;

        Outcome(Job.Status status, long saved) {
            this.status = status;
            this.saved = saved;
        }
    }

    private Outcome runOne(ContentResolver cr, final Task t, ConvertOptions opts, Map<String, String> existing,
                           final long doneBefore, int count) {
        boolean compress = opts.mode == ConvertOptions.Mode.COMPRESS;
        if (!compress && !t.isCso) return finish(t, Job.Status.SKIPPED, "Already an ISO");

        String base = opts.naming == ConvertOptions.Naming.GAME_TITLE && t.suggestedName != null
                ? t.suggestedName : Docs.stripExtension(t.fileName);
        String outName = base + (compress ? ".cso" : ".iso");

        String existingId = existing.get(outName);
        if (existingId != null) {
            if (existingId.equals(Docs.documentIdOrNull(t.uri))) {
                return finish(t, Job.Status.SKIPPED, "Output would overwrite the original");
            }
            if (opts.existing == ConvertOptions.Existing.SKIP) {
                return finish(t, Job.Status.SKIPPED, "Already exists in the output folder");
            }
            if (opts.existing == ConvertOptions.Existing.REPLACE) {
                try {
                    DocumentsContract.deleteDocument(cr,
                            DocumentsContract.buildDocumentUriUsingTree(opts.outputTree, existingId));
                    existing.remove(outName);
                } catch (Exception e) {
                    return finish(t, Job.Status.FAILED, "Couldn't replace the existing file");
                }
            }
        }

        long free = Docs.availableBytes(cr, opts.outputTree);
        long need = compress ? Math.max(MIN_FREE_BYTES, t.size / 4) : t.size + MIN_FREE_BYTES;
        if (free >= 0 && free < need) {
            return finish(t, Job.Status.FAILED, "Not enough free space (" + size(free) + " free)");
        }

        setStatus(t.id, Job.Status.CONVERTING, null, 0);
        updateNotification(t, count, 0, "Converting");
        Uri out = null;
        final long startTime = System.currentTimeMillis();
        try {
            out = DocumentsContract.createDocument(cr, Docs.treeRoot(opts.outputTree),
                    "application/octet-stream", outName);
            if (out == null) throw new IOException("Couldn't create the output file");
            String newId = Docs.documentIdOrNull(out);
            if (newId != null) existing.put(Docs.displayName(cr, out), newId);

            Converter.Result r;
            try (ParcelFileDescriptor ipfd = open(cr, t.uri, "r");
                 ParcelFileDescriptor opfd = open(cr, out, "rw");
                 FileChannel ic = new FileInputStream(ipfd.getFileDescriptor()).getChannel();
                 FileChannel oc = new FileOutputStream(opfd.getFileDescriptor()).getChannel();
                 ImageReader reader = ImageReader.open(ic)) {
                if (!compress && !reader.isCompressed()) throw new IOException("Not a CSO file");
                Converter.Listener l = new ProgressSink(t, count, doneBefore, startTime);
                int threads = Runtime.getRuntime().availableProcessors();
                r = compress
                        ? Converter.compress(reader, oc, opts.blockSize(), opts.level, threads, cancel, l)
                        : Converter.decompress(reader, oc, cancel, l);
            }

            boolean verified = false;
            if (opts.verify) {
                setStatus(t.id, Job.Status.VERIFYING, null, 0);
                updateNotification(t, count, 0, "Verifying");
                try (ParcelFileDescriptor vpfd = open(cr, out, "r");
                     FileChannel vc = new FileInputStream(vpfd.getFileDescriptor()).getChannel();
                     ImageReader written = ImageReader.open(vc)) {
                    Converter.verify(written, r.inputBytes, r.crc, cancel, new ProgressSink(t, count, doneBefore, startTime));
                }
                verified = true;
            }

            String msg = String.format(Locale.US, "%s → %s (%.0f%%) in %s%s",
                    size(r.inputBytes), size(r.outputBytes), 100.0 * r.outputBytes / r.inputBytes,
                    duration(System.currentTimeMillis() - startTime), verified ? " · verified" : "");
            if (opts.deleteOriginal && verified) {
                boolean deleted;
                try {
                    deleted = DocumentsContract.deleteDocument(cr, t.uri);
                } catch (Exception e) {
                    deleted = false;
                }
                msg += deleted ? " · original deleted" : " · couldn't delete original";
            }
            final long outBytes = r.outputBytes;
            final String m = msg;
            ConvertState.INSTANCE.update(new Runnable() {
                @Override
                public void run() {
                    Job j = ConvertState.INSTANCE.find(t.id);
                    if (j == null) return;
                    j.status = Job.Status.DONE;
                    j.message = m;
                    j.progress = 1;
                    j.outputBytes = outBytes;
                }
            });
            return new Outcome(Job.Status.DONE, compress ? Math.max(0, r.inputBytes - r.outputBytes) : 0);
        } catch (Exception e) {
            deleteQuietly(cr, out);
            if (e instanceof InterruptedIOException || cancel.get()) {
                return finish(t, Job.Status.CANCELLED, "Cancelled");
            }
            return finish(t, Job.Status.FAILED, friendlyError(e));
        }
    }

    private static ParcelFileDescriptor open(ContentResolver cr, Uri uri, String mode) throws IOException {
        ParcelFileDescriptor pfd;
        try {
            pfd = cr.openFileDescriptor(uri, mode);
        } catch (SecurityException e) {
            throw new IOException("Permission denied — add the file again");
        }
        if (pfd == null) throw new IOException("Couldn't open file");
        return pfd;
    }

    static String friendlyError(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (m.contains("ENOSPC") || m.contains("No space left")) return "Storage is full";
        if (m.contains("EACCES") || m.contains("Permission")) return "Permission denied — add the file again";
        if (m.contains("ENOENT") || e instanceof java.io.FileNotFoundException) return "File not found";
        if (m.contains("Out of memory")) return "Out of memory";
        return m;
    }

    private Outcome finish(final Task t, Job.Status s, String msg) {
        setStatus(t.id, s, msg, s == Job.Status.SKIPPED ? 1 : -1);
        return new Outcome(s, 0);
    }

    private void setStatus(final long id, final Job.Status s, final String msg, final float progress) {
        ConvertState.INSTANCE.update(new Runnable() {
            @Override
            public void run() {
                Job j = ConvertState.INSTANCE.find(id);
                if (j == null) return;
                j.status = s;
                j.message = msg;
                if (progress >= 0) j.progress = progress;
            }
        });
    }

    /** Throttles progress to the UI and notification, and keeps speed and ETA. */
    private final class ProgressSink implements Converter.Listener {
        private final Task task;
        private final int count;
        private final long doneBefore;
        private final long startTime;
        private long lastUi;
        private long lastNotify;
        private double speed;
        private long lastBytes;
        private long lastTime;

        ProgressSink(Task task, int count, long doneBefore, long startTime) {
            this.task = task;
            this.count = count;
            this.doneBefore = doneBefore;
            this.startTime = startTime;
            this.lastTime = System.currentTimeMillis();
        }

        @Override
        public void onProgress(final Converter.Phase phase, final long done, final long total, long written) {
            long now = System.currentTimeMillis();
            if (phase == Converter.Phase.CONVERTING && now - lastTime >= 500) {
                double inst = (done - lastBytes) * 1000.0 / (now - lastTime);
                speed = speed == 0 ? inst : speed * 0.8 + inst * 0.2;
                lastBytes = done;
                lastTime = now;
            }
            if (now - lastUi >= 200 || done == total) {
                lastUi = now;
                final float frac = total > 0 ? (float) done / total : 0;
                final double sp = speed;
                ConvertState.INSTANCE.update(new Runnable() {
                    @Override
                    public void run() {
                        ConvertState st = ConvertState.INSTANCE;
                        Job j = st.find(task.id);
                        if (j != null) j.progress = frac;
                        if (phase == Converter.Phase.CONVERTING) {
                            st.runDone = doneBefore + done;
                            st.bytesPerSec = sp;
                            long remaining = st.runTotal - st.runDone;
                            st.etaSeconds = sp > 1 ? (long) (remaining / sp) : -1;
                        }
                    }
                });
            }
            if (now - lastNotify >= 1000) {
                lastNotify = now;
                updateNotification(task, count, total > 0 ? (int) (done * 100 / total) : 0,
                        phase == Converter.Phase.VERIFYING ? "Verifying" : "Converting");
            }
        }
    }

    private void updateNotification(Task t, int count, int pct, String verb) {
        String title = t.suggestedName != null ? t.suggestedName : t.fileName;
        String text = verb + " · " + pct + "%" + (count > 1 ? "  (queue: " + count + " files)" : "");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFY_PROGRESS, progressNotification(title, text, pct, false));
    }

    private Notification.Builder baseBuilder(String channel) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, channel);
        } else {
            b = legacyBuilder(channel);
        }
        return b.setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand_pink))
                .setContentIntent(pi);
    }

    /** Android 7.x has no channels; map each channel to a priority instead. */
    @SuppressWarnings("deprecation")
    private Notification.Builder legacyBuilder(String channel) {
        return new Notification.Builder(this).setPriority(CHANNEL_PROGRESS.equals(channel)
                ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
    }

    private Notification progressNotification(String title, String text, int pct, boolean indeterminate) {
        PendingIntent cancelPi = PendingIntent.getService(this, 1,
                new Intent(this, ConvertService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = baseBuilder(CHANNEL_PROGRESS)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, indeterminate)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancelPi).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PROGRESS, "Conversion progress",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Conversion finished",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + (s / 60 % 60) + "m";
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
