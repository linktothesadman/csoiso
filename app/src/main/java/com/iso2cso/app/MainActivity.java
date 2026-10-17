package com.iso2cso.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements ConvertState.Listener {

    private static final int REQ_PICK_ISOS = 1;
    private static final int REQ_PICK_FOLDER = 2;
    private static final String PREF_TREE = "outputTree";
    private static final String PREF_BIG_BLOCKS = "bigBlocks";
    private static final String PREF_EXHAUSTIVE = "exhaustive";

    private final ArrayList<Uri> inputs = new ArrayList<>();
    private Uri outputTree;
    private SharedPreferences prefs;

    private TextView inputsText;
    private TextView folderText;
    private RadioButton smallest;
    private RadioButton compatible;
    private CheckBox exhaustive;
    private CheckBox deleteOriginal;
    private Button convertButton;
    private Button cancelButton;
    private ProgressBar progress;
    private TextView statusText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        final View root = findViewById(R.id.root);
        final int pad = root.getPaddingTop();
        // Android 15+ draws edge to edge for apps targeting SDK 35; keep content clear of the bars.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(pad + insets.getSystemWindowInsetLeft(),
                        pad + insets.getSystemWindowInsetTop(),
                        pad + insets.getSystemWindowInsetRight(),
                        pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        inputsText = findViewById(R.id.inputs_text);
        folderText = findViewById(R.id.folder_text);
        smallest = findViewById(R.id.mode_smallest);
        compatible = findViewById(R.id.mode_compatible);
        exhaustive = findViewById(R.id.exhaustive);
        deleteOriginal = findViewById(R.id.delete_original);
        convertButton = findViewById(R.id.convert);
        cancelButton = findViewById(R.id.cancel);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.status);
        logText = findViewById(R.id.log);

        boolean big = prefs.getBoolean(PREF_BIG_BLOCKS, true);
        smallest.setChecked(big);
        compatible.setChecked(!big);
        exhaustive.setChecked(prefs.getBoolean(PREF_EXHAUSTIVE, true));

        String tree = prefs.getString(PREF_TREE, null);
        if (tree != null && hasPermission(Uri.parse(tree))) outputTree = Uri.parse(tree);

        findViewById(R.id.pick_isos).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_PICK_ISOS);
            }
        });
        findViewById(R.id.pick_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_PICK_FOLDER);
            }
        });
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

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        refreshSelection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConvertState.INSTANCE.setListener(this);
    }

    @Override
    protected void onPause() {
        ConvertState.INSTANCE.setListener(null);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (requestCode == REQ_PICK_ISOS) {
            inputs.clear();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    inputs.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                inputs.add(data.getData());
            }
            for (Uri u : inputs) takePermission(u, flags);
        } else if (requestCode == REQ_PICK_FOLDER && data.getData() != null) {
            outputTree = data.getData();
            takePermission(outputTree, flags);
            prefs.edit().putString(PREF_TREE, outputTree.toString()).apply();
        }
        refreshSelection();
    }

    private void takePermission(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            try {
                // Some providers only grant read access; that's enough for inputs.
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // the temporary grant still lasts while the app is alive
            }
        }
    }

    private boolean hasPermission(Uri tree) {
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return true;
        }
        return false;
    }

    private void refreshSelection() {
        if (inputs.isEmpty()) {
            inputsText.setText(R.string.no_isos);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Uri u : inputs) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(ConvertService.displayName(getContentResolver(), u));
            }
            inputsText.setText(sb);
        }
        folderText.setText(outputTree == null ? getString(R.string.no_folder) : folderLabel(outputTree));
        updateButtons(ConvertState.INSTANCE.running);
    }

    private static String folderLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String volume = colon > 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        String prefix = "primary".equals(volume) ? "Internal storage" : volume;
        return path.isEmpty() ? prefix : prefix + "/" + path;
    }

    private void startConversion() {
        if (inputs.isEmpty()) {
            Toast.makeText(this, R.string.no_isos, Toast.LENGTH_SHORT).show();
            return;
        }
        if (outputTree == null) {
            Toast.makeText(this, R.string.no_folder, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean big = smallest.isChecked();
        prefs.edit()
                .putBoolean(PREF_BIG_BLOCKS, big)
                .putBoolean(PREF_EXHAUSTIVE, exhaustive.isChecked())
                .apply();
        Intent i = new Intent(this, ConvertService.class)
                .setAction(ConvertService.ACTION_START)
                .putParcelableArrayListExtra(ConvertService.EXTRA_INPUTS, new ArrayList<>(inputs))
                .putExtra(ConvertService.EXTRA_TREE, outputTree)
                .putExtra(ConvertService.EXTRA_BLOCK_SIZE,
                        big ? CsoEncoder.BLOCK_PPSSPP : CsoEncoder.BLOCK_PSP)
                .putExtra(ConvertService.EXTRA_EXHAUSTIVE, exhaustive.isChecked())
                .putExtra(ConvertService.EXTRA_DELETE_ORIGINAL, deleteOriginal.isChecked());
        startForegroundService(i);
        updateButtons(true);
    }

    private void updateButtons(boolean running) {
        convertButton.setEnabled(!running);
        cancelButton.setVisibility(running ? View.VISIBLE : View.GONE);
        findViewById(R.id.pick_isos).setEnabled(!running);
        findViewById(R.id.pick_folder).setEnabled(!running);
    }

    @Override
    public void onStateChanged(ConvertState st) {
        updateButtons(st.running);
        if (st.running) {
            int pct = st.bytesTotal > 0 ? (int) (st.bytesRead * 1000 / st.bytesTotal) : 0;
            progress.setIndeterminate(st.bytesTotal == 0);
            progress.setProgress(pct);
            progress.setVisibility(View.VISIBLE);
            String ratio = st.bytesRead > 0
                    ? String.format(Locale.US, " · %.1f%% of original", 100.0 * st.bytesWritten / st.bytesRead)
                    : "";
            statusText.setText(String.format(Locale.US, "%s%s\n%s / %s%s",
                    st.fileCount > 1 ? "(" + (st.fileIndex + 1) + "/" + st.fileCount + ") " : "",
                    st.currentName,
                    Formatter.formatFileSize(this, st.bytesRead),
                    Formatter.formatFileSize(this, st.bytesTotal),
                    ratio));
        } else {
            progress.setVisibility(View.GONE);
            statusText.setText(st.totalIn > 0
                    ? getString(R.string.total_saved,
                            Formatter.formatFileSize(this, st.totalIn - st.totalOut),
                            Formatter.formatFileSize(this, st.totalIn),
                            Formatter.formatFileSize(this, st.totalOut))
                    : "");
        }
        logText.setText(st.log);
    }
}
