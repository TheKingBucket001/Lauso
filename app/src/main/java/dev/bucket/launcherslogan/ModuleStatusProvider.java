package dev.bucket.launcherslogan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates a short-lived, caller-verified handshake between the management app and Launcher.
 * A historical Hook heartbeat is deliberately insufficient: every app launch needs a new reply.
 */
public final class ModuleStatusProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.bucket.launcherslogan.status";
    public static final String LAUNCHER_PACKAGE = "com.android.launcher";
    public static final String ACTION_VERIFY_LAUNCHER =
            "dev.bucket.launcherslogan.action.VERIFY_LAUNCHER";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String PERMISSION_VERIFY_LAUNCHER =
            "dev.bucket.launcherslogan.permission.VERIFY_LAUNCHER";

    private static final String PATH = "status";
    private static final String METHOD_BEGIN = "begin_launcher_verification";
    private static final String METHOD_CONFIRM = "confirm_launcher_verification";
    private static final String EXTRA_ACCEPTED = "accepted";
    private static final long REQUEST_TTL_MS = 8_000L;
    private static final Object VERIFICATION_LOCK = new Object();
    private static PendingVerification pendingVerification;

    private static final class PendingVerification {
        final String requestId;
        final long requestedAt;
        final CountDownLatch confirmed = new CountDownLatch(1);
        boolean accepted;

        PendingVerification(String requestId, long requestedAt) {
            this.requestId = requestId;
            this.requestedAt = requestedAt;
        }
    }

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + PATH);
    }

    /** Starts a new verification round and invalidates every older Launcher response. */
    public static String beginLauncherVerification(Context context) {
        try {
            Bundle result = context.getContentResolver().call(uri(), METHOD_BEGIN, null, null);
            if (result == null || !result.getBoolean(EXTRA_ACCEPTED, false)) return null;
            return result.getString(EXTRA_REQUEST_ID);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Called only from the dynamically registered receiver in the injected Launcher process. */
    public static boolean confirmLauncherVerification(Context context, String requestId) {
        if (requestId == null || requestId.isEmpty()) return false;
        try {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_REQUEST_ID, requestId);
            Bundle result = context.getContentResolver().call(uri(), METHOD_CONFIRM, null, extras);
            return result != null && result.getBoolean(EXTRA_ACCEPTED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Waits for this exact request without polling or persisting transient state. */
    public static boolean awaitLauncherVerification(String requestId, long timeoutMs) {
        if (requestId == null || requestId.isEmpty()) return false;
        PendingVerification pending;
        synchronized (VERIFICATION_LOCK) {
            pending = pendingVerification;
            if (pending == null || !requestId.equals(pending.requestId)) return false;
        }
        try {
            pending.confirmed.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (VERIFICATION_LOCK) {
            return pendingVerification == pending
                    && pending.accepted
                    && isFresh(pending, SystemClock.elapsedRealtime());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) return Bundle.EMPTY;
        if (METHOD_BEGIN.equals(method)) {
            return isOwnAppCaller() ? begin() : Bundle.EMPTY;
        }
        if (METHOD_CONFIRM.equals(method)) {
            return isLauncherCaller()
                    ? confirm(extras == null ? null : extras.getString(EXTRA_REQUEST_ID))
                    : Bundle.EMPTY;
        }
        return Bundle.EMPTY;
    }

    private Bundle begin() {
        String requestId = UUID.randomUUID().toString();
        PendingVerification next = new PendingVerification(requestId, SystemClock.elapsedRealtime());
        synchronized (VERIFICATION_LOCK) {
            pendingVerification = next;
        }
        Bundle result = new Bundle();
        result.putBoolean(EXTRA_ACCEPTED, true);
        result.putString(EXTRA_REQUEST_ID, requestId);
        return result;
    }

    private Bundle confirm(String requestId) {
        boolean accepted = false;
        synchronized (VERIFICATION_LOCK) {
            PendingVerification pending = pendingVerification;
            if (pending != null
                    && requestId != null
                    && requestId.equals(pending.requestId)
                    && isFresh(pending, SystemClock.elapsedRealtime())) {
                pending.accepted = true;
                pending.confirmed.countDown();
                accepted = true;
            }
        }
        Bundle result = new Bundle();
        result.putBoolean(EXTRA_ACCEPTED, accepted);
        return result;
    }

    private static boolean isFresh(PendingVerification pending, long now) {
        if (pending == null) return false;
        long elapsed = now - pending.requestedAt;
        return pending.requestedAt >= 0L
                && elapsed >= 0L
                && elapsed <= REQUEST_TTL_MS;
    }

    private boolean isOwnAppCaller() {
        return Binder.getCallingUid() == Process.myUid();
    }

    private boolean isLauncherCaller() {
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String packageName : packages) {
            if (LAUNCHER_PACKAGE.equals(packageName)) return true;
        }
        return false;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
