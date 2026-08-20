package dev.bucket.launcherslogan;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

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

    private RootAccess() {
    }

    public static Result check() {
        String lastError = "无法找到可用的 su";
        for (String suCommand : SU_COMMANDS) {
            try {
                return checkWith(suCommand);
            } catch (java.io.IOException error) {
                if (isMissingExecutable(suCommand)) {
                    lastError = "无法找到可用的 su";
                    continue;
                }
                return new Result(false, "无法调用 su", -1);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Result(false, "Root 检测被中断", -1);
            }
        }
        return new Result(false, lastError, -1);
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
        return runWithSu("/system/bin/am force-stop com.android.launcher");
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

    private static Result checkWith(String suCommand) throws java.io.IOException, InterruptedException {
        Process process = start(suCommand, "id");
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                return new Result(false, "Root 授权请求超时", -1);
            }
            String output = readOutput(process);
            if (process.exitValue() == 0 && ROOT_ID.matcher(output).find()) {
                return new Result(true, "已获得 uid=0", 0);
            }
            return new Result(false, process.exitValue() == 0
                    ? "su 返回的身份不是 uid=0" : "Root 请求被拒绝", process.exitValue());
        } finally {
            closeProcess(process);
        }
    }

    private static Result runWithSu(String command) {
        String lastMessage = "无法找到可用的 su";
        for (String suCommand : SU_COMMANDS) {
            try {
                Process process = start(suCommand, command);
                try {
                    if (!process.waitFor(4, TimeUnit.SECONDS)) {
                        return new Result(false, "Root 命令超时", -1);
                    }
                    String output = readOutput(process).trim();
                    int exitCode = process.exitValue();
                    return new Result(exitCode == 0, output, exitCode);
                } finally {
                    closeProcess(process);
                }
            } catch (java.io.IOException error) {
                if (isMissingExecutable(suCommand)) {
                    lastMessage = "无法找到可用的 su";
                    continue;
                }
                return new Result(false, "无法调用 su", -1);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Result(false, "Root 命令被中断", -1);
            }
        }
        return new Result(false, lastMessage, -1);
    }

    private static Process start(String suCommand, String command) throws java.io.IOException {
        return new ProcessBuilder(suCommand, "-c", command)
                .redirectErrorStream(true)
                .start();
    }

    private static String readOutput(Process process) throws java.io.IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        return output.toString();
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
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(500, TimeUnit.MILLISECONDS);
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (java.io.IOException ignored) {
            // Best-effort cleanup after a root command completes.
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
