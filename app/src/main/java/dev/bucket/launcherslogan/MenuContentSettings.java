package dev.bucket.launcherslogan;

import android.content.Context;
import android.provider.Settings;

/** Cross-process, Launcher-only visibility preferences for the long-press menu. */
public final class MenuContentSettings {
    public static final String DISABLE_LONG_PRESS_MENU = "disable_long_press_menu";
    public static final String HIDE_DEEP_SHORTCUTS = "hide_long_press_deep_shortcuts";
    public static final String HIDE_PRIVACY_LOCK = "hide_long_press_privacy_lock";
    public static final String HIDE_CLOSE_PRIVACY_LOCK = "hide_long_press_close_privacy_lock";
    public static final String HIDE_APP_INFO = "hide_long_press_app_info";
    public static final String HIDE_APP_SHARE = "hide_long_press_app_share";
    public static final String HIDE_APP_EDIT = "hide_long_press_app_edit";
    public static final String HIDE_SERVICE_CARD = "hide_long_press_service_card";

    private static final String KEY_PREFIX = "bucket_launcher_slogan_menu_v1_";
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled";

    private MenuContentSettings() {
    }

    public static boolean read(Context context, String option) {
        if (!isKnown(option)) {
            return false;
        }
        try {
            String value = Settings.Global.getString(context.getContentResolver(), keyFor(option));
            return ENABLED.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hidesSystemItem(Context context, String option) {
        return read(context, option);
    }

    public static String keyFor(String option) {
        if (!isKnown(option)) {
            throw new IllegalArgumentException("Unknown menu option");
        }
        return KEY_PREFIX + option;
    }

    public static String encode(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    public static boolean isKnown(String option) {
        return DISABLE_LONG_PRESS_MENU.equals(option)
                || HIDE_DEEP_SHORTCUTS.equals(option)
                || HIDE_PRIVACY_LOCK.equals(option)
                || HIDE_CLOSE_PRIVACY_LOCK.equals(option)
                || HIDE_APP_INFO.equals(option)
                || HIDE_APP_SHARE.equals(option)
                || HIDE_APP_EDIT.equals(option)
                || HIDE_SERVICE_CARD.equals(option);
    }

}
