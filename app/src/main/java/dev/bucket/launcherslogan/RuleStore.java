package dev.bucket.launcherslogan;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Versioned, cross-UID rule storage readable by the Launcher process. */
public final class RuleStore {
    public static final String GLOBAL_KEY = "bucket_launcher_slogan_rules_v1";
    private static final String PREFIX_V1 = "v1:";
    private static final String PREFIX_V2 = "v2:";
    private static final String TAG = "LauncherSlogan";
    private static final int MAX_RULES = 64;
    private static final int MAX_TITLE_LENGTH = 72;
    private static final int MAX_SUBTITLE_LENGTH = 88;
    private static final int MAX_TOAST_LENGTH = 120;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private RuleStore() {
    }

    public static List<SloganRule> read(Context context) {
        try {
            String encoded = Settings.Global.getString(
                    context.getContentResolver(), GLOBAL_KEY);
            return decode(encoded);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read launcher slogan rules", error);
            return Collections.emptyList();
        }
    }

    public static SloganRule find(Context context, String packageName) {
        if (packageName == null) return null;
        for (SloganRule rule : read(context)) {
            if (packageName.equals(rule.getPackageName())) return rule;
        }
        return null;
    }

    public static String encode(List<SloganRule> rules) {
        JSONArray array = new JSONArray();
        ArrayList<SloganRule> ordered = new ArrayList<>();
        if (rules != null) ordered.addAll(rules);
        ordered.sort(Comparator.comparing(SloganRule::getPackageName));
        for (SloganRule rule : ordered) {
            if (array.length() >= MAX_RULES) break;
            if (rule == null) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("packageName", rule.getPackageName());
                item.put("title", rule.getTitle());
                item.put("subtitle", rule.getSubtitle());
                item.put("toastMessage", rule.getToastMessage());
                array.put(item);
            } catch (Throwable ignored) {
                // Validation below makes this unreachable for UI-created rules.
            }
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("version", 2);
            payload.put("rules", array);
        } catch (Throwable ignored) {
            return PREFIX_V2 + Base64.encodeToString(
                    "{\"version\":2,\"rules\":[]}".getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
        }
        return PREFIX_V2 + Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public static List<SloganRule> decode(String value) {
        if (value == null || (!value.startsWith(PREFIX_V1) && !value.startsWith(PREFIX_V2))) {
            return Collections.emptyList();
        }
        try {
            String prefix = value.startsWith(PREFIX_V2) ? PREFIX_V2 : PREFIX_V1;
            String json = new String(Base64.decode(value.substring(prefix.length()), Base64.NO_WRAP),
                    StandardCharsets.UTF_8);
            JSONObject payload = new JSONObject(json);
            int version = payload.optInt("version", -1);
            if (version != 1 && version != 2) return Collections.emptyList();
            JSONArray array = payload.optJSONArray("rules");
            if (array == null) return Collections.emptyList();
            ArrayList<SloganRule> rules = new ArrayList<>();
            for (int i = 0; i < array.length() && rules.size() < MAX_RULES; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String packageName = item.optString("packageName", "").trim();
                String title = cleanTitle(item.optString("title", item.optString("message", "")));
                String subtitle = cleanSubtitle(item.optString("subtitle", ""));
                String toastMessage = cleanToastMessage(item.optString("toastMessage", ""));
                if (isValid(packageName, title, subtitle, toastMessage)) {
                    rules.add(new SloganRule(packageName, title, subtitle, toastMessage));
                }
            }
            rules.sort(Comparator.comparing(SloganRule::getPackageName));
            return Collections.unmodifiableList(rules);
        } catch (Throwable error) {
            Log.w(TAG, "Ignoring malformed launcher slogan rules", error);
            return Collections.emptyList();
        }
    }

    public static boolean isValid(
            String packageName, String title, String subtitle, String toastMessage) {
        return packageName != null
                && PACKAGE_PATTERN.matcher(packageName).matches()
                && title != null
                && !cleanTitle(title).isEmpty()
                && cleanTitle(title).length() <= MAX_TITLE_LENGTH
                && subtitle != null
                && cleanSubtitle(subtitle).length() <= MAX_SUBTITLE_LENGTH
                && toastMessage != null
                && cleanToastMessage(toastMessage).length() <= MAX_TOAST_LENGTH;
    }

    public static String cleanTitle(String value) {
        return cleanText(value);
    }

    public static String cleanSubtitle(String value) {
        return cleanText(value);
    }

    public static String cleanToastMessage(String value) {
        return cleanText(value);
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
    }

    public static int maxTitleLength() {
        return MAX_TITLE_LENGTH;
    }

    public static int maxSubtitleLength() {
        return MAX_SUBTITLE_LENGTH;
    }

    public static int maxToastLength() {
        return MAX_TOAST_LENGTH;
    }

    public static int maxRules() {
        return MAX_RULES;
    }
}
