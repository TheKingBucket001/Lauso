package dev.bucket.launcherslogan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;

/** Reports and exposes proof that the Launcher process actually loaded this module. */
public final class ModuleStatusProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.bucket.launcherslogan.status";
    private static final String PATH = "status";
    private static final String METHOD_REPORT = "report_launcher_loaded";
    private static final String EXTRA_PACKAGE = "reporting_package";
    private static final String PREFS = "module_status";
    private static final String KEY_BOOT = "hook_boot_count";
    private static final String KEY_LOADED = "hook_loaded_at";
    private static final String KEY_VERSION = "hook_module_version";
    private static final String LAUNCHER = "com.android.launcher";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + PATH);
    }

    public static void reportLauncherLoaded(android.content.Context context) {
        try {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_PACKAGE, LAUNCHER);
            context.getContentResolver().call(uri(), METHOD_REPORT, null, extras);
        } catch (Throwable ignored) {
            // The Provider may not be available during the earliest process startup.
        }
    }

    public static HookStatus read(android.content.Context context) {
        int boot = getBootCount(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        int reportedBoot = prefs.getInt(KEY_BOOT, -1);
        long loadedAt = prefs.getLong(KEY_LOADED, 0L);
        int version = prefs.getInt(KEY_VERSION, -1);
        // A Launcher process can retain the previous module heartbeat while an APK
        // update is waiting for that process to be recycled. The gate is meant to
        // prove that LSPosed loaded LauSo in this boot, not to deadlock on an
        // otherwise harmless version transition; keep the version for diagnostics.
        boolean ready = boot >= 0
                && reportedBoot == boot
                && loadedAt > 0L;
        return new HookStatus(ready, loadedAt, reportedBoot, version);
    }

    private static int getBootCount(android.content.Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_REPORT.equals(method) || extras == null || getContext() == null
                || !LAUNCHER.equals(extras.getString(EXTRA_PACKAGE))
                || !isAllowedCaller()) {
            return Bundle.EMPTY;
        }
        int boot = getBootCount(getContext());
        boolean saved = boot >= 0 && getContext().getSharedPreferences(PREFS, 0).edit()
                .putInt(KEY_BOOT, boot)
                .putLong(KEY_LOADED, System.currentTimeMillis())
                .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
                .commit();
        Bundle result = new Bundle();
        result.putBoolean("accepted", saved);
        return result;
    }

    private boolean isAllowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (LAUNCHER.equals(packageName)) return true;
        }
        return false;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    public static final class HookStatus {
        public final boolean loadedForCurrentBoot;
        public final long loadedAt;
        public final int reportedBoot;
        public final int moduleVersion;

        HookStatus(boolean loadedForCurrentBoot, long loadedAt, int reportedBoot, int moduleVersion) {
            this.loadedForCurrentBoot = loadedForCurrentBoot;
            this.loadedAt = loadedAt;
            this.reportedBoot = reportedBoot;
            this.moduleVersion = moduleVersion;
        }
    }
}
