package dev.bucket.launcherslogan;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Regression checks run with Android's real Base64 and JSON implementations. */
public final class RuleStoreAudit {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }
    private static String payload(String json, int version) {
        return "v" + version + ":" + Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
    public static void main(String[] args) {
        SloganRule a = new SloganRule("com.example.a", "中文主标语", "副标语", "点击反馈");
        SloganRule b = new SloganRule("com.example.b", "B", "", "");
        check(RuleStore.decode(RuleStore.encode(null)).isEmpty(), "null list encodes empty");
        check(RuleStore.decode(RuleStore.encode(Collections.emptyList())).isEmpty(), "empty list round trip");
        List<SloganRule> encoded = RuleStore.decode(RuleStore.encode(Arrays.asList(null, b, null, a, null)));
        check(encoded.equals(Arrays.asList(a, b)), "null entries ignored before sorting; Unicode round trip");
        String duplicates = "{\"version\":2,\"rules\":[{\"packageName\":\"com.example.a\",\"title\":\"first\"},{\"packageName\":\"com.example.a\",\"title\":\"second\"}]}";
        List<SloganRule> decoded = RuleStore.decode(payload(duplicates, 2));
        check(decoded.size() == 1 && decoded.get(0).getTitle().equals("first"), "duplicate package retains first valid rule");
        String legacy = "{\"version\":1,\"rules\":[{\"packageName\":\"com.example.a\",\"message\":\"legacy\"}]}";
        check(RuleStore.decode(payload(legacy, 1)).get(0).getTitle().equals("legacy"), "v1 message compatibility");
        check(RuleStore.decode("v2:not base64").isEmpty() && RuleStore.decode("other").isEmpty(), "malformed inputs rejected");
        ArrayList<SloganRule> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(new SloganRule("com.example.p" + i, "T", "", ""));
        check(RuleStore.decode(RuleStore.encode(many)).size() == 64, "rule count bounded");
        check(!RuleStore.isValid("bad package", "T", "", "")
                && !RuleStore.isValid("com.example.a", new String(new char[73]).replace('\0', 'T'), "", "")
                && RuleStore.cleanTitle(" x\n\t y ").equals("x  y"), "validation and control cleanup");
        check(a.equals(new SloganRule("com.example.a", "中文主标语", "副标语", "点击反馈"))
                && a.hashCode() == new SloganRule("com.example.a", "中文主标语", "副标语", "点击反馈").hashCode(), "rule equality and hash consistent");
        System.out.println("PASS total=" + passed);
    }
}
