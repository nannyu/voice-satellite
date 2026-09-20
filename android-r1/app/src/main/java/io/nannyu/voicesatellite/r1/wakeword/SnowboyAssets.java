package io.nannyu.voicesatellite.r1.wakeword;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Copies official Snowboy resources out of APK assets to a real filesystem path
 * (native constructor requires absolute paths) and picks the wake model.
 *
 * Preference order for models under {@code files/snowboy/}:
 *   1. any {@code *.pmdl} (Chinese personal model — production)
 *   2. {@code snowboy.umdl} (official English smoke model)
 */
public final class SnowboyAssets {
    public static final String ASSET_DIR = "snowboy";
    public static final String COMMON_RES = "common.res";
    public static final String SMOKE_UMDL = "snowboy.umdl";

    public static final class Bundle {
        public final File commonRes;
        public final File model;
        public final boolean personalModel;

        Bundle(File commonRes, File model, boolean personalModel) {
            this.commonRes = commonRes;
            this.model = model;
            this.personalModel = personalModel;
        }
    }

    private SnowboyAssets() {}

    public static File workDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), ASSET_DIR);
    }

    /**
     * Ensures common.res (+ bundled umdl if present) are on disk, then returns
     * a usable model bundle, or null if common.res / model are missing.
     */
    public static Bundle prepare(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File dir = workDir(app);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create snowboy work dir: " + dir);
        }

        syncAssetFile(app, COMMON_RES, new File(dir, COMMON_RES), true);
        // Smoke umdl: copy once; do not overwrite a user-replaced file.
        syncAssetFile(app, SMOKE_UMDL, new File(dir, SMOKE_UMDL), false);
        // Optional personal model shipped in assets (e.g. wakeword.pmdl).
        copyAssetPmdls(app, dir);

        File common = new File(dir, COMMON_RES);
        if (!common.isFile()) return null;

        File personal = firstPmdl(dir);
        if (personal != null) {
            return new Bundle(common, personal, true);
        }
        File umdl = new File(dir, SMOKE_UMDL);
        if (umdl.isFile()) {
            return new Bundle(common, umdl, false);
        }
        return null;
    }

    private static File firstPmdl(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".pmdl")) {
                return f;
            }
        }
        return null;
    }

    private static void copyAssetPmdls(Context app, File dir) throws IOException {
        AssetManager am = app.getAssets();
        String[] names;
        try {
            names = am.list(ASSET_DIR);
        } catch (IOException e) {
            return;
        }
        if (names == null) return;
        for (String name : names) {
            if (name.toLowerCase(Locale.US).endsWith(".pmdl")) {
                syncAssetFile(app, name, new File(dir, name), false);
            }
        }
    }

    private static void syncAssetFile(
            Context app, String assetName, File dest, boolean overwrite) throws IOException {
        if (dest.exists() && !overwrite) return;
        AssetManager am = app.getAssets();
        String assetPath = ASSET_DIR + "/" + assetName;
        InputStream in;
        try {
            in = am.open(assetPath);
        } catch (IOException missing) {
            return;
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            try (InputStream src = in; OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = src.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            if (dest.exists() && !dest.delete()) {
                throw new IOException("cannot replace " + dest);
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("cannot move " + tmp + " -> " + dest);
            }
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Diagnostic listing of models found on disk. */
    public static List<String> listModels(Context context) {
        List<String> out = new ArrayList<>();
        File[] files = workDir(context).listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".pmdl") || n.endsWith(".umdl")) {
                out.add(f.getName());
            }
        }
        return out;
    }
}
