package dev.bucket.launcherslogan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed-command Root boundary used by the management UI. */
public final class RootAccess {
    private static final String[] SU_COMMANDS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
    };
    private static final Pattern ROOT_ID = Pattern.compile(
            "(^|\\s)uid=0(?:\\([^\\r\\n]*\\))?(?=\\s|$)");
    private static final long COMMAND_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(4);
    private static final long OUTPUT_POLL_NS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private RootAccess() {
    }

    public static Result check() {
        return runWithSu("id", true);
    }

    public static Result saveRules(List<SloganRule> rules) {
        if (rules == null || rules.size() > RuleStore.maxRules()) {
            return new Result(false, "规则数量不能超过 " + RuleStore.maxRules() + " 条", -1);
        }
        String value = RuleStore.encode(rules);
        if (!value.matches("v[12]:[A-Za-z0-9+/=]+")) {
            return new Result(false, "规则编码不符合安全格式", -1);
        }
        return runWithSu("/system/bin/settings put global " + RuleStore.GLOBAL_KEY + " " + value);
    }

    public static Result refreshLauncher() {
        return runWithSu("/system/bin/am force-stop --user current com.android.launcher");
    }

    public static Result saveMaterial(boolean translucent) {
        return runWithSu("/system/bin/settings put global "
                + MenuMaterialSettings.GLOBAL_KEY + " "
                + MenuMaterialSettings.encodePanel(translucent));
    }

    public static Result saveCompact(boolean compact) {
        return runWithSu("/system/bin/settings put global "
                + MenuMaterialSettings.COMPACT_KEY + " "
                + MenuMaterialSettings.encodeCompact(compact));
    }

    public static Result saveVisualComfort(boolean visualComfort) {
        return runWithSu("/system/bin/settings put global "
                + MenuMaterialSettings.VISUAL_COMFORT_KEY + " "
                + MenuMaterialSettings.encodeVisualComfort(visualComfort));
    }

    public static Result saveDisableBackdrop(boolean disableBackdrop) {
        return runWithSu("/system/bin/settings put global "
                + MenuMaterialSettings.DISABLE_BACKDROP_KEY + " "
                + MenuMaterialSettings.encodeDisableBackdrop(disableBackdrop));
    }

    public static Result saveMenuContent(String option, boolean enabled) {
        if (!MenuContentSettings.isKnown(option)) {
            return new Result(false, "未知菜单设置", -1);
        }
        return runWithSu("/system/bin/settings put global "
                + MenuContentSettings.keyFor(option) + " "
                + MenuContentSettings.encode(enabled));
    }

    private static Result runWithSu(String command) {
        return runWithSu(command, false);
    }

    private static Result runWithSu(String command, boolean verifyIdentity) {
        if (Thread.currentThread().isInterrupted()) {
            return interruptedResult(verifyIdentity);
        }
        long deadline = System.nanoTime() + COMMAND_TIMEOUT_NS;
        for (String suCommand : SU_COMMANDS) {
            if (System.nanoTime() - deadline >= 0) return timeoutResult(verifyIdentity);
            try {
                return runProcess(start(suCommand, command), verifyIdentity, deadline);
            } catch (java.io.IOException error) {
                if (isMissingExecutable(suCommand)) {
                    continue;
                }
                return new Result(false, "无法调用 su", -1);
            }
        }
        return new Result(false, "无法找到可用的 su", -1);
    }

    private static Process start(String suCommand, String command) throws java.io.IOException {
        return new ProcessBuilder(suCommand, "-c", command)
                .redirectErrorStream(true)
                .start();
    }

    /** One budget covers draining the pipe and waiting; no blocking reader thread is needed. */
    private static Result runProcess(Process process, boolean verifyIdentity, long deadline)
            throws java.io.IOException {
        try {
            try {
                // All supported commands are non-interactive. Do not leave their stdin open.
                process.getOutputStream().close();
                String output = readOutput(process, deadline).trim();
                int exitCode = process.exitValue();
                if (verifyIdentity) {
                    if (exitCode == 0 && ROOT_ID.matcher(output).find()) {
                        return new Result(true, "已获得 uid=0", 0);
                    }
                    return new Result(false, exitCode == 0
                            ? "su 返回的身份不是 uid=0" : "Root 请求被拒绝", exitCode);
                }
                return new Result(exitCode == 0, output, exitCode);
            } finally {
                closeProcess(process);
            }
        } catch (TimeoutException error) {
            return timeoutResult(verifyIdentity);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return interruptedResult(verifyIdentity);
        }
    }

    private static Result timeoutResult(boolean verifyIdentity) {
        return new Result(false, verifyIdentity ? "Root 授权请求超时" : "Root 命令超时", -1);
    }

    private static Result interruptedResult(boolean verifyIdentity) {
        return new Result(false, verifyIdentity ? "Root 检测被中断" : "Root 命令被中断", -1);
    }

    private static String readOutput(Process process, long deadline)
            throws java.io.IOException, InterruptedException, TimeoutException {
        InputStream input = process.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int bytesAtExit = -1;
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException("Root command interrupted");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Root command timed out");
            // A descendant may inherit stdout. Once our process exits, capture only what is
            // already readable; waiting for EOF could otherwise outlive the command forever.
            if (bytesAtExit < 0 && !process.isAlive()) bytesAtExit = input.available();
            int available = bytesAtExit < 0 ? input.available() : bytesAtExit;
            if (available > 0) {
                // available() promises bytes that can be read without blocking. This is the
                // only consumer, so cap each read and recheck time/interruption every chunk.
                int count = input.read(buffer, 0, Math.min(buffer.length, available));
                if (count <= 0) {
                    if (bytesAtExit >= 0) bytesAtExit = 0;
                    else process.waitFor(Math.min(remaining, OUTPUT_POLL_NS), TimeUnit.NANOSECONDS);
                    continue;
                }
                if (bytesAtExit >= 0) bytesAtExit -= count;
                int stored = Math.min(count, MAX_OUTPUT_BYTES - output.size());
                output.write(buffer, 0, stored);
                truncated |= stored < count;
            } else if (bytesAtExit >= 0) {
                return new String(output.toByteArray(), StandardCharsets.UTF_8)
                        + (truncated ? "\n（输出已截断）" : "");
            } else {
                process.waitFor(Math.min(remaining, OUTPUT_POLL_NS), TimeUnit.NANOSECONDS);
            }
        }
    }

    private static boolean isMissingExecutable(String suCommand) {
        if (suCommand.indexOf('/') >= 0) return !new File(suCommand).isFile();
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(File.pathSeparator)) {
            if (new File(directory, suCommand).isFile()) return false;
        }
        return true;
    }

    private static void closeProcess(Process process) throws InterruptedException {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } finally {
            try (InputStream input = process.getInputStream();
                 InputStream error = process.getErrorStream();
                 OutputStream output = process.getOutputStream()) {
                // Close all three streams even if waiting is interrupted or one close fails.
            } catch (java.io.IOException ignored) {
                // Best-effort cleanup after a root command completes.
            }
        }
    }

    public static final class Result {
        public final boolean granted;
        public final String message;
        public final int exitCode;

        Result(boolean granted, String message, int exitCode) {
            this.granted = granted;
            this.message = message;
            this.exitCode = exitCode;
        }
    }
}
