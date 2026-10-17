package com.iso2cso.app;

import android.os.Handler;
import android.os.Looper;

/**
 * Process-wide conversion status shared between {@link ConvertService} and the UI.
 * Fields are only touched on the main thread; workers go through {@link #update}.
 */
public final class ConvertState {

    public interface Listener {
        void onStateChanged(ConvertState state);
    }

    public static final ConvertState INSTANCE = new ConvertState();

    public boolean running;
    public int fileIndex;
    public int fileCount;
    public String currentName = "";
    public long bytesRead;
    public long bytesTotal;
    public long bytesWritten;
    public long totalIn;
    public long totalOut;
    public final StringBuilder log = new StringBuilder();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private ConvertState() {
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStateChanged(this);
    }

    public void log(String line) {
        if (log.length() > 0) log.append('\n');
        log.append(line);
    }

    /** Run {@code change} on the main thread, then notify the UI. */
    public void update(final Runnable change) {
        main.post(new Runnable() {
            @Override
            public void run() {
                change.run();
                if (listener != null) listener.onStateChanged(ConvertState.this);
            }
        });
    }
}
