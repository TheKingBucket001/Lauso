package dev.bucket.launcherslogan;

import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Exercises user text at storage boundaries, including strict UTF-8 encoding. */
public final class TextInputLimitHostAudit {
    private static int passed;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }

    private static void assertEncodable(String text) throws Exception {
        StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
    }

    public static void main(String[] args) throws Exception {
        String emoji = "\uD83D\uDE00";
        check(TextInputLimit.truncate("", 0).isEmpty(), "empty text and zero limit");
        check(TextInputLimit.truncate(emoji, 1).isEmpty(), "one-unit limit never leaves half an emoji");
        check(TextInputLimit.truncate(emoji, 2).equals(emoji), "complete emoji at exact limit retained");
        check(TextInputLimit.truncate("中文ABC", 4).equals("中文AB"), "BMP text keeps existing UTF-16 length semantics");
        for (int limit : new int[] {72, 88, 120}) {
            String prefix = repeat("A", limit - 1);
            String result = TextInputLimit.truncate(prefix + emoji, limit);
            check(result.equals(prefix), "crossing emoji excluded at field limit " + limit);
            assertEncodable(result);
            String exact = repeat("中", limit - 2) + emoji;
            check(TextInputLimit.truncate(exact, limit).equals(exact),
                    "whole emoji retained at field limit " + limit);
            assertEncodable(TextInputLimit.truncate(exact, limit));
        }
        String original = "原始\uD83D\uDE00e\u0301";
        check(TextInputLimit.truncate(original, original.length() + 1).equals(original),
                "under-limit text unchanged without normalization");
        String mixed = "A" + emoji + "中\uD83D\uDC69\u200D\uD83D\uDCBB\uD83C\uDDE8\uD83C\uDDF3e\u0301Z";
        for (int limit = 0; limit <= mixed.length() + 1; limit++) {
            int expectedEnd = 0;
            while (expectedEnd < mixed.length()) {
                int next = expectedEnd + Character.charCount(mixed.codePointAt(expectedEnd));
                if (next > limit) break;
                expectedEnd = next;
            }
            String result = TextInputLimit.truncate(mixed, limit);
            if (!result.equals(mixed.substring(0, expectedEnd))) {
                throw new AssertionError("not the longest complete-code-point prefix at limit " + limit);
            }
            assertEncodable(result);
        }
        check(true, "all cut positions of mixed user text remain valid UTF-8 and within UTF-16 limit");
        try {
            TextInputLimit.truncate("text", -1);
            throw new AssertionError("negative limit accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "negative limits rejected");
        }
        System.out.println("TextInputLimit host audit: " + passed + " checks passed");
    }
}
