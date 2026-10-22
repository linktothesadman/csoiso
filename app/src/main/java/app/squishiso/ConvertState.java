package app.squishiso;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide queue and progress shared between {@link ConvertService} and the UI.
 * Fields are only touched on the main thread; workers go through {@link #update}.
 */
public final class ConvertState {

    public interface Listener {
        void onStateChanged(ConvertState state);
    }

    public static final ConvertState INSTANCE = new ConvertState();

    public final List<Job> jobs = new ArrayList<>();
    public boolean running;
    public boolean cancelling;
    /** Bytes of input processed / to process in the current run, for overall progress. */
    public long runDone;
    public long runTotal;
    public double bytesPerSec;
    public long etaSeconds = -1;
    public String summary = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private ConvertState() {
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStateChanged(this);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Job find(long id) {
        for (Job j : jobs) if (j.id == id) return j;
        return null;
    }

    public void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onStateChanged(this);
    }

    /** Run {@code change} on the main thread, then notify the UI. */
    public void update(final Runnable change) {
        main.post(new Runnable() {
            @Override
            public void run() {
                change.run();
                notifyChanged();
            }
        });
    }
}
