package app.squishiso;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ConvertState.Listener {

    private static final int REQ_PICK_FILES = 1;
    private static final int REQ_PICK_FOLDER = 2;
    private static final int REQ_PICK_INPUT_FOLDER = 3;

    /** Reads game info off the UI thread, one file at a time to keep storage access sequential. */
    private static final ExecutorService INFO_LOADER = Executors.newSingleThreadExecutor();

    private final ConvertState state = ConvertState.INSTANCE;
    private final Map<Long, View> rows = new HashMap<>();
    private SharedPreferences prefs;
    private ConvertOptions opts;
    private boolean binding;

    private LinearLayout queueList;
    private View queueEmpty;
    private TextView queueSummary, folderText, statusText, statText, heroSub, footer;
    private RadioButton modeCompress, modeDecompress, blockCompatible, blockSmallest, levelMax, levelFast;
    private Spinner naming, existing;
    private CheckBox verify, deleteOriginal;
    private Button convertButton, cancelButton, addFiles, addFolder, clearDone, clearAll, pickFolder;
    private ProgressBar progress;
    private View compressionCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        opts = ConvertOptions.load(prefs);
        if (opts.outputTree != null && !hasWritePermission(opts.outputTree)) opts.outputTree = null;

        final View root = findViewById(R.id.root);
        final int pad = root.getPaddingTop();
        // Android 15+ draws edge to edge for apps targeting SDK 35; keep content clear of the bars.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                        pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        queueList = findViewById(R.id.queue_list);
        queueEmpty = findViewById(R.id.queue_empty);
        queueSummary = findViewById(R.id.queue_summary);
        folderText = findViewById(R.id.folder_text);
        statusText = findViewById(R.id.status);
        statText = findViewById(R.id.stat);
        heroSub = findViewById(R.id.hero_sub);
        footer = findViewById(R.id.footer);
        modeCompress = findViewById(R.id.mode_compress);
        modeDecompress = findViewById(R.id.mode_decompress);
        blockCompatible = findViewById(R.id.block_compatible);
        blockSmallest = findViewById(R.id.block_smallest);
        levelMax = findViewById(R.id.level_max);
        levelFast = findViewById(R.id.level_fast);
        naming = findViewById(R.id.naming);
        existing = findViewById(R.id.existing);
        verify = findViewById(R.id.verify);
        deleteOriginal = findViewById(R.id.delete_original);
        convertButton = findViewById(R.id.convert);
        cancelButton = findViewById(R.id.cancel);
        addFiles = findViewById(R.id.add_files);
        addFolder = findViewById(R.id.add_folder);
        clearDone = findViewById(R.id.clear_done);
        clearAll = findViewById(R.id.clear_all);
        pickFolder = findViewById(R.id.pick_folder);
        progress = findViewById(R.id.progress);
        compressionCard = findViewById(R.id.compression_card);

        bindOptions();
        wireListeners();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        footer.setText(getString(R.string.app_name) + " " + versionName() + " · About & licenses");
        if (savedInstanceState == null) handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        state.addListener(this);
    }

    @Override
    protected void onPause() {
        state.removeListener(this);
        super.onPause();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.licenses, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.licenses)
                                .setMessage(R.string.licenses_body)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                })
                .show();
    }

    // ---------- options ----------

    private void bindOptions() {
        binding = true;
        modeCompress.setChecked(opts.mode == ConvertOptions.Mode.COMPRESS);
        modeDecompress.setChecked(opts.mode == ConvertOptions.Mode.DECOMPRESS);
        blockCompatible.setChecked(opts.pspCompatible);
        blockSmallest.setChecked(!opts.pspCompatible);
        levelMax.setChecked(opts.level == CsoEncoder.Level.MAX);
        levelFast.setChecked(opts.level == CsoEncoder.Level.FAST);
        naming.setSelection(opts.naming.ordinal());
        existing.setSelection(opts.existing.ordinal());
        verify.setChecked(opts.verify || opts.deleteOriginal);
        verify.setEnabled(!opts.deleteOriginal);
        deleteOriginal.setChecked(opts.deleteOriginal);
        boolean compress = opts.mode == ConvertOptions.Mode.COMPRESS;
        compressionCard.setVisibility(compress ? View.VISIBLE : View.GONE);
        convertButton.setText(compress ? R.string.convert_cso : R.string.convert_iso);
        folderText.setText(opts.outputTree == null ? getString(R.string.no_folder) : Docs.folderLabel(opts.outputTree));
        binding = false;
    }

    private void readOptions() {
        if (binding) return;
        opts.mode = modeDecompress.isChecked() ? ConvertOptions.Mode.DECOMPRESS : ConvertOptions.Mode.COMPRESS;
        opts.pspCompatible = !blockSmallest.isChecked();
        opts.level = levelFast.isChecked() ? CsoEncoder.Level.FAST : CsoEncoder.Level.MAX;
        opts.naming = ConvertOptions.Naming.values()[Math.max(0, naming.getSelectedItemPosition())];
        opts.existing = ConvertOptions.Existing.values()[Math.max(0, existing.getSelectedItemPosition())];
        opts.deleteOriginal = deleteOriginal.isChecked();
        opts.verify = verify.isChecked() || opts.deleteOriginal;
        opts.save(prefs);
        bindOptions();
        renderQueue();
    }

    private void wireListeners() {
        RadioGroup.OnCheckedChangeListener radio = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                readOptions();
            }
        };
        ((RadioGroup) findViewById(R.id.mode_group)).setOnCheckedChangeListener(radio);
        ((RadioGroup) findViewById(R.id.block_group)).setOnCheckedChangeListener(radio);
        ((RadioGroup) findViewById(R.id.level_group)).setOnCheckedChangeListener(radio);
        AdapterView.OnItemSelectedListener spin = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                readOptions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        naming.setOnItemSelectedListener(spin);
        existing.setOnItemSelectedListener(spin);
        CompoundButton.OnCheckedChangeListener check = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                readOptions();
            }
        };
        verify.setOnCheckedChangeListener(check);
        deleteOriginal.setOnCheckedChangeListener(check);

        addFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_PICK_FILES);
            }
        });
        addFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_INPUT_FOLDER);
            }
        });
        pickFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER);
            }
        });
        clearDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Iterator<Job> it = state.jobs.iterator(); it.hasNext(); ) {
                    Job j = it.next();
                    if (j.isFinished()) it.remove();
                }
                state.notifyChanged();
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state.running) return;
                state.jobs.clear();
                state.summary = "";
                state.notifyChanged();
            }
        });
        View.OnClickListener about = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        };
        findViewById(R.id.about).setOnClickListener(about);
        footer.setOnClickListener(about);
        convertButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConversion();
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, ConvertService.class)
                        .setAction(ConvertService.ACTION_CANCEL));
            }
        });
    }

    // ---------- adding files ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (requestCode == REQ_PICK_FILES) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) takePermission(u, rw);
            addUris(uris, null);
        } else if (requestCode == REQ_PICK_FOLDER && data.getData() != null) {
            takePermission(data.getData(), rw);
            opts.outputTree = data.getData();
            opts.save(prefs);
            bindOptions();
        } else if (requestCode == REQ_PICK_INPUT_FOLDER && data.getData() != null) {
            final Uri tree = data.getData();
            takePermission(tree, rw);
            Toast.makeText(this, "Scanning folder…", Toast.LENGTH_SHORT).show();
            INFO_LOADER.execute(new Runnable() {
                @Override
                public void run() {
                    final List<String> names = new ArrayList<>();
                    final List<Uri> found = Docs.scanForImages(getContentResolver(), tree, 4, names);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (found.isEmpty()) {
                                Toast.makeText(MainActivity.this, "No .iso or .cso files in that folder",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                addUris(found, names);
                            }
                        }
                    });
                }
            });
        }
    }

    /** Files shared to the app or opened with it. */
    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        for (Uri u : uris) takePermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        addUris(uris, null);
        setIntent(new Intent());
    }

    private void addUris(List<Uri> uris, List<String> knownNames) {
        int added = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri u = uris.get(i);
            boolean dup = false;
            for (Job j : state.jobs) if (j.uri.equals(u)) dup = true;
            if (dup) continue;
            String name = knownNames != null ? knownNames.get(i) : Docs.displayName(getContentResolver(), u);
            final Job job = new Job(u, name);
            state.jobs.add(job);
            added++;
            INFO_LOADER.execute(new Runnable() {
                @Override
                public void run() {
                    loadInfo(job);
                }
            });
        }
        if (added == 0 && !uris.isEmpty()) Toast.makeText(this, "Already in the queue", Toast.LENGTH_SHORT).show();
        state.summary = "";
        state.notifyChanged();
    }

    /** Runs on INFO_LOADER: size, CSO detection, title, disc ID and icon. */
    private void loadInfo(final Job job) {
        android.content.ContentResolver cr = getApplicationContext().getContentResolver();
        long size = -1;
        boolean cso = job.isCso;
        GameInfo info = null;
        Bitmap icon = null;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(job.uri, "r");
             FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
             ImageReader r = ImageReader.open(ch)) {
            size = r.size();
            cso = r.isCompressed();
            info = GameInfo.read(r);
            if (info.icon != null) icon = BitmapFactory.decodeByteArray(info.icon, 0, info.icon.length);
        } catch (Exception e) {
            size = Docs.size(cr, job.uri);
        }
        final long fSize = size;
        final boolean fCso = cso;
        final GameInfo fInfo = info;
        final Bitmap fIcon = icon;
        state.update(new Runnable() {
            @Override
            public void run() {
                job.size = fSize;
                job.isCso = fCso;
                job.infoLoaded = true;
                if (fInfo != null) {
                    job.title = fInfo.title;
                    job.discId = fInfo.discId;
                    job.suggestedName = fInfo.suggestedName();
                    if (!fInfo.iso9660 && job.message == null && job.status == Job.Status.QUEUED) {
                        job.message = "Doesn't look like a disc image";
                    }
                }
                job.icon = fIcon;
            }
        });
    }

    private void takePermission(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            try {
                // Some providers only grant read access; that's enough for inputs.
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // the temporary grant still lasts while the app is alive
            }
        }
    }

    private boolean hasWritePermission(Uri tree) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return true;
        }
        return false;
    }

    // ---------- converting ----------

    private void startConversion() {
        int pending = 0;
        for (Job j : state.jobs) if (!j.isFinished()) pending++;
        if (pending == 0) {
            Toast.makeText(this, state.jobs.isEmpty() ? "Add some files first" : "Everything in the queue is done",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (opts.outputTree == null || !hasWritePermission(opts.outputTree)) {
            Toast.makeText(this, R.string.no_folder, Toast.LENGTH_SHORT).show();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER);
            return;
        }
        if (opts.deleteOriginal) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete_confirm_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            launchService();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            launchService();
        }
    }

    private void launchService() {
        Intent i = new Intent(this, ConvertService.class).setAction(ConvertService.ACTION_START);
        opts.toIntent(i);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    // ---------- rendering ----------

    @Override
    public void onStateChanged(ConvertState st) {
        boolean running = st.running;
        convertButton.setEnabled(!running);
        cancelButton.setVisibility(running ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(!st.cancelling);
        cancelButton.setText(st.cancelling ? "Cancelling…" : getString(R.string.cancel));
        clearAll.setEnabled(!running && !st.jobs.isEmpty());
        boolean anyDone = false;
        for (Job j : st.jobs) anyDone |= j.isFinished();
        clearDone.setEnabled(anyDone);
        pickFolder.setEnabled(!running);
        for (int id : new int[] {R.id.mode_compress, R.id.mode_decompress, R.id.block_compatible,
                R.id.block_smallest, R.id.level_max, R.id.level_fast, R.id.naming, R.id.existing,
                R.id.delete_original}) {
            findViewById(id).setEnabled(!running);
        }
        verify.setEnabled(!running && !opts.deleteOriginal);

        if (running) {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(st.runTotal <= 0);
            progress.setProgress(st.runTotal > 0 ? (int) (st.runDone * 1000 / st.runTotal) : 0);
            StringBuilder sb = new StringBuilder();
            sb.append(Formatter.formatShortFileSize(this, st.runDone)).append(" of ")
                    .append(Formatter.formatShortFileSize(this, st.runTotal));
            if (st.bytesPerSec > 0) {
                sb.append(" · ").append(Formatter.formatShortFileSize(this, (long) st.bytesPerSec)).append("/s");
            }
            if (st.etaSeconds >= 0) sb.append(" · ").append(ConvertService.duration(st.etaSeconds * 1000)).append(" left");
            statusText.setText(sb);
        } else {
            progress.setVisibility(View.GONE);
            statusText.setText(st.summary);
        }
        long saved = prefs.getLong("lifetimeSaved", 0);
        statText.setText(saved > 0 ? getString(R.string.lifetime_saved, Formatter.formatShortFileSize(this, saved))
                : getString(R.string.tagline));
        heroSub.setText(saved > 0 ? R.string.saved_sub : R.string.tagline_sub);
        renderQueue();
    }

    private void renderQueue() {
        // Drop rows for removed jobs, add rows for new ones, keep order.
        List<Long> ids = new ArrayList<>();
        for (Job j : state.jobs) ids.add(j.id);
        for (Iterator<Map.Entry<Long, View>> it = rows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, View> e = it.next();
            if (!ids.contains(e.getKey())) {
                queueList.removeView(e.getValue());
                it.remove();
            }
        }
        long total = 0;
        int pending = 0;
        for (int i = 0; i < state.jobs.size(); i++) {
            Job j = state.jobs.get(i);
            View row = rows.get(j.id);
            if (row == null) {
                row = LayoutInflater.from(this).inflate(R.layout.item_job, queueList, false);
                rows.put(j.id, row);
            }
            if (queueList.getChildAt(i) != row) {
                if (row.getParent() != null) ((ViewGroup) row.getParent()).removeView(row);
                queueList.addView(row, i);
            }
            bindRow(row, j);
            if (j.size > 0) total += j.size;
            if (!j.isFinished()) pending++;
        }
        queueEmpty.setVisibility(state.jobs.isEmpty() ? View.VISIBLE : View.GONE);
        queueSummary.setText(state.jobs.isEmpty() ? "" : state.jobs.size() + (state.jobs.size() == 1 ? " file · " : " files · ")
                + Formatter.formatShortFileSize(this, total) + (pending < state.jobs.size() ? " · " + pending + " to do" : ""));
    }

    private void bindRow(View row, final Job j) {
        ImageView icon = row.findViewById(R.id.icon);
        if (j.icon != null) icon.setImageBitmap(j.icon);
        else icon.setImageDrawable(null);

        ((TextView) row.findViewById(R.id.title)).setText(j.displayTitle());
        StringBuilder sub = new StringBuilder();
        if (j.discId != null) sub.append(j.discId).append(" · ");
        sub.append(j.size >= 0 ? Formatter.formatShortFileSize(this, j.size) : "…");
        sub.append(j.isCso ? " · CSO" : " · ISO");
        if (j.title != null) sub.append(" · ").append(j.fileName);
        ((TextView) row.findViewById(R.id.subtitle)).setText(sub);

        TextView msg = row.findViewById(R.id.message);
        String text = j.message;
        int color = getColor(R.color.text_secondary);
        switch (j.status) {
            case CONVERTING: text = "Converting… " + Math.round(j.progress * 100) + "%"; color = getColor(R.color.accent); break;
            case VERIFYING: text = "Verifying… " + Math.round(j.progress * 100) + "%"; color = getColor(R.color.accent); break;
            case DONE: color = getColor(R.color.success); text = "✓ " + j.message; break;
            case FAILED: color = getColor(R.color.error); text = "✗ " + j.message; break;
            case CANCELLED: text = "Cancelled"; break;
            case SKIPPED: text = "Skipped: " + j.message; break;
            default:
                if (opts.mode == ConvertOptions.Mode.DECOMPRESS && j.infoLoaded && !j.isCso) {
                    text = "Already an ISO; will be skipped";
                }
                break;
        }
        msg.setVisibility(text != null ? View.VISIBLE : View.GONE);
        msg.setText(text);
        msg.setTextColor(color);

        ProgressBar pb = row.findViewById(R.id.job_progress);
        pb.setVisibility(j.isActive() ? View.VISIBLE : View.GONE);
        pb.setProgress((int) (j.progress * 1000));

        View remove = row.findViewById(R.id.remove);
        remove.setVisibility(j.isActive() || (state.running && j.status == Job.Status.QUEUED) ? View.INVISIBLE : View.VISIBLE);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.jobs.remove(j);
                state.notifyChanged();
            }
        });
    }
}
