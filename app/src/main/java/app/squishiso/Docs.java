package app.squishiso;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Storage Access Framework helpers. */
final class Docs {

    private Docs() {
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

    static long size(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
            // unknown
        }
        return -1;
    }

    static boolean isImageName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".iso") || n.endsWith(".cso");
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static Uri treeRoot(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /** Display name -> document id for the direct children of the tree's root folder. */
    static Map<String, String> children(ContentResolver cr, Uri tree) {
        Map<String, String> out = new HashMap<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = cr.query(kids, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) out.put(c.getString(1), c.getString(0));
        } catch (Exception ignored) {
            // treat as empty
        }
        return out;
    }

    /** Finds .iso/.cso files under a picked folder, up to a few levels deep. */
    static List<Uri> scanForImages(ContentResolver cr, Uri tree, int maxDepth, List<String> namesOut) {
        List<Uri> found = new ArrayList<>();
        scan(cr, tree, DocumentsContract.getTreeDocumentId(tree), maxDepth, found, namesOut);
        return found;
    }

    private static void scan(ContentResolver cr, Uri tree, String docId, int depth, List<Uri> found,
                             List<String> names) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(kids, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            while (c != null && c.moveToNext() && found.size() < 500) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth > 0) scan(cr, tree, id, depth - 1, found, names);
                } else if (name != null && isImageName(name)) {
                    found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    names.add(name);
                }
            }
        } catch (Exception ignored) {
            // unreadable folder; skip it
        }
    }

    /** Free bytes on the volume holding the tree, or -1 if the provider doesn't say. */
    static long availableBytes(ContentResolver cr, Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        int colon = docId.indexOf(':');
        String rootId = colon > 0 ? docId.substring(0, colon) : docId;
        Uri roots = DocumentsContract.buildRootsUri(tree.getAuthority());
        try (Cursor c = cr.query(roots, new String[] {DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (rootId.equals(c.getString(0)) && !c.isNull(1)) return c.getLong(1);
            }
        } catch (Exception ignored) {
            // unknown
        }
        return -1;
    }

    /** Human-readable label like "Internal storage/PSP/ISO" for a tree URI. */
    static String folderLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String volume = colon > 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        String prefix = "primary".equals(volume) ? "Internal storage" : volume.isEmpty() ? "Storage" : "SD card";
        return path.isEmpty() ? prefix : prefix + "/" + path;
    }

    static String documentIdOrNull(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (Exception e) {
            return null;
        }
    }
}
