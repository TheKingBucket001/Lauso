package dev.bucket.launcherslogan;

import android.content.Context;
import android.provider.Settings;

/** Cross-process preferences for the ColorOS popup appearance controls. */
public final class MenuMaterialSettings {
    public static final String GLOBAL_KEY = "bucket_launcher_slogan_material_v1";
    public static final String COMPACT_KEY = "bucket_launcher_slogan_compact_v1";
    public static final String VISUAL_COMFORT_KEY =
            "bucket_launcher_slogan_visual_comfort_v1";
    public static final String DISABLE_BACKDROP_KEY =
            "bucket_launcher_slogan_disable_backdrop_v2";
    private static final String NATIVE = "native";
    private static final String TRANSLUCENT = "translucent";
    private static final String LEGACY_ACRYLIC = "acrylic";
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled";

    private MenuMaterialSettings() {
    }

    /** All optional visual changes are off until the user explicitly enables them. */
    public static boolean readPanel(Context context) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), GLOBAL_KEY);
            return TRANSLUCENT.equals(value) || LEGACY_ACRYLIC.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean readCompact(Context context) {
        try {
            return ENABLED.equals(Settings.Global.getString(
                    context.getContentResolver(), COMPACT_KEY));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the compact slogan should use the reduced optical bottom gap. */
    public static boolean readVisualComfort(Context context) {
        try {
            return ENABLED.equals(Settings.Global.getString(
                    context.getContentResolver(), VISUAL_COMFORT_KEY));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True only when the user has chosen to remove ColorOS's native full-screen blur. */
    public static boolean readDisableBackdrop(Context context) {
        try {
            String value = Settings.Global.getString(
                    context.getContentResolver(), DISABLE_BACKDROP_KEY);
            return ENABLED.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String encodePanel(boolean translucent) {
        return translucent ? TRANSLUCENT : NATIVE;
    }

    public static String encodeCompact(boolean compact) {
        return compact ? ENABLED : DISABLED;
    }

    public static String encodeVisualComfort(boolean visualComfort) {
        return visualComfort ? ENABLED : DISABLED;
    }

    public static String encodeDisableBackdrop(boolean disableBackdrop) {
        return disableBackdrop ? ENABLED : DISABLED;
    }
}
