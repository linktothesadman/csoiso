package app.squishiso;

import android.graphics.Bitmap;
import android.net.Uri;

/** One file in the queue. Mutated only on the main thread (see {@link ConvertState}). */
public final class Job {

    public enum Status { QUEUED, CONVERTING, VERIFYING, DONE, SKIPPED, FAILED, CANCELLED }

    private static long nextId = 1;

    public final long id = nextId++;
    public final Uri uri;
    public String fileName;
    public long size = -1;
    public boolean isCso;
    public boolean infoLoaded;
    public String title;
    public String discId;
    public String suggestedName;
    public Bitmap icon;

    public Status status = Status.QUEUED;
    public String message;
    public float progress;
    public long outputBytes;

    public Job(Uri uri, String fileName) {
        this.uri = uri;
        this.fileName = fileName;
        this.isCso = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".cso");
    }

    public boolean isFinished() {
        return status == Status.DONE || status == Status.SKIPPED;
    }

    public boolean isActive() {
        return status == Status.CONVERTING || status == Status.VERIFYING;
    }

    public String displayTitle() {
        return title != null && !title.isEmpty() ? title : fileName;
    }
}
