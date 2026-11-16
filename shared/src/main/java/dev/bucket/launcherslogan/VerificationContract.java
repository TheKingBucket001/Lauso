package dev.bucket.launcherslogan;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Wire contract and acknowledgement client shared by the app and injected Launcher. */
public final class VerificationContract {
    public static final String AUTHORITY = "dev.bucket.launcherslogan.status";
    public static final String LAUNCHER_PACKAGE = "com.android.launcher";
    public static final String ACTION_VERIFY_LAUNCHER =
            "dev.bucket.launcherslogan.action.VERIFY_LAUNCHER";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String PERMISSION_VERIFY_LAUNCHER =
            "dev.bucket.launcherslogan.permission.VERIFY_LAUNCHER";
    public static final String METHOD_BEGIN = "begin_launcher_verification";
    public static final String METHOD_CONFIRM = "confirm_launcher_verification";
    public static final String EXTRA_ACCEPTED = "accepted";

    private VerificationContract() {
    }

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/status");
    }

    /** Called by the dynamically registered receiver in the injected Launcher process. */
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
}
