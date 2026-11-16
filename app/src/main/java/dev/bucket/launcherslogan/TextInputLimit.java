package dev.bucket.launcherslogan;

/** Applies the storage's UTF-16 length limit without splitting a surrogate pair. */
public final class TextInputLimit {
    private TextInputLimit() {
    }

    public static String truncate(String value, int maxLength) {
        if (maxLength < 0) throw new IllegalArgumentException("maxLength must be non-negative");
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
