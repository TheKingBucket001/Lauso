package dev.bucket.launcherslogan;

import java.util.Objects;

/** A single launcher menu rule. */
public final class SloganRule {
    private final String packageName;
    private final String title;
    private final String subtitle;
    private final String toastMessage;

    public SloganRule(String packageName, String title, String subtitle, String toastMessage) {
        this.packageName = packageName == null ? "" : packageName;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.toastMessage = toastMessage == null ? "" : toastMessage;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getToastMessage() {
        return toastMessage;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SloganRule)) return false;
        SloganRule rule = (SloganRule) other;
        return packageName.equals(rule.packageName)
                && title.equals(rule.title)
                && subtitle.equals(rule.subtitle)
                && toastMessage.equals(rule.toastMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, title, subtitle, toastMessage);
    }
}
