package dev.bucket.launcherslogan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Real pipe/timeout regression tests; child commands use this JVM and never invoke su. */
public final class RootProcessHostAudit {
    private static int passed;
    private static Method runner;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
    private static Process child(String mode) throws Exception {
        return new ProcessBuilder(javaExecutable(), "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                RootProcessHostAudit.class.getName(), mode).redirectErrorStream(true).start();
    }
    private static RootAccess.Result run(Process process, long timeoutMs, boolean identity) throws Exception {
        try {
            return (RootAccess.Result) runner.invoke(null, process, identity,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        } catch (InvocationTargetException failure) {
            Throwable error = failure.getCause();
            if (error instanceof Exception) throw (Exception) error;
            throw new AssertionError(error);
        }
    }
    private static void childMain(String mode) throws Exception {
        switch (mode) {
            case "unicode": System.out.write("中文输出\nsecond line\n".getBytes(StandardCharsets.UTF_8)); break;
            case "root": System.out.println("uid=0(root) gid=0(root)"); break;
            case "nonroot": System.out.println("uid=1000(shell) gid=1000(shell)"); break;
            case "failure": System.err.print("failed output"); System.exit(7); break;
            case "large":
                byte[] buffer = new byte[8192];
                java.util.Arrays.fill(buffer, (byte) 'X');
                for (int i = 0; i < 16; i++) {
                    System.out.write(buffer);
                    System.err.write(buffer);
                }
                break;
            case "sleep": Thread.sleep(15_000L); break;
            case "continuous":
                byte[] bytes = new byte[8192];
                java.util.Arrays.fill(bytes, (byte) 'Y');
                while (true) System.out.write(bytes);
            case "descendant":
                Process holder = new ProcessBuilder(javaExecutable(), "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                        RootProcessHostAudit.class.getName(), "sleep").inheritIO().start();
                System.out.println("holder=" + holder.pid());
                break;
            case "stdin":
                if (System.in.read() != -1) throw new AssertionError("stdin must be EOF");
                System.out.print("stdin closed");
                break;
            default: throw new IllegalArgumentException(mode);
        }
    }

    /** Simulates a terminated parent while an inherited pipe never reaches EOF. */
    private static final class InheritedPipeProcess extends Process {
        final AtomicBoolean blockingReadAttempted = new AtomicBoolean();
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        final ByteArrayInputStream error = new ByteArrayInputStream(new byte[0]);
        final InputStream input = new InputStream() {
            final byte[] ready = "already ready".getBytes(StandardCharsets.UTF_8);
            int offset;
            @Override public int available() { return ready.length - offset; }
            @Override public int read() {
                if (offset < ready.length) return ready[offset++];
                blockingReadAttempted.set(true);
                throw new AssertionError("Must not wait for descendant EOF");
            }
            @Override public int read(byte[] buffer, int start, int count) {
                if (count > available()) throw new AssertionError("Read exceeds available bytes");
                System.arraycopy(ready, offset, buffer, start, count);
                offset += count;
                return count;
            }
        };
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { childMain(args[0]); return; }
        runner = RootAccess.class.getDeclaredMethod("runProcess", Process.class, boolean.class, long.class);
        runner.setAccessible(true);

        RootAccess.Result unicode = run(child("unicode"), 4_000, false);
        check(unicode.granted && unicode.message.equals("中文输出\nsecond line"), "UTF-8 output and line breaks preserved");
        check(run(child("root"), 4_000, true).granted && !run(child("nonroot"), 4_000, true).granted,
                "root identity semantics unchanged");
        RootAccess.Result failure = run(child("failure"), 4_000, false);
        check(!failure.granted && failure.exitCode == 7 && failure.message.equals("failed output"),
                "nonzero exit and merged stderr preserved");
        Process large = child("large");
        RootAccess.Result largeResult = run(large, 4_000, false);
        check(largeResult.granted && !large.isAlive() && largeResult.message.length() < 66_000
                && largeResult.message.startsWith("XXXX") && largeResult.message.contains("输出已截断"),
                "256 KiB of stdout/stderr drain without pipe deadlock or unbounded memory");
        check(run(child("stdin"), 4_000, false).message.equals("stdin closed"),
                "non-interactive child receives stdin EOF");

        InheritedPipeProcess inherited = new InheritedPipeProcess();
        long inheritedStart = System.nanoTime();
        RootAccess.Result inheritedResult = run(inherited, 4_000, false);
        check(inheritedResult.granted && inheritedResult.message.equals("already ready")
                && !inherited.blockingReadAttempted.get()
                && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inheritedStart) < 500,
                "inherited stdout captures available bytes without reading descendant EOF");

        Process continuous = child("continuous");
        long continuousStart = System.nanoTime();
        RootAccess.Result timedOut = run(continuous, 350, false);
        long timeoutMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - continuousStart);
        check(!timedOut.granted && timedOut.exitCode == -1 && timeoutMs < 1_500 && !continuous.isAlive(),
                "continuous output shares the wait deadline and kills timed-out child elapsedMs=" + timeoutMs);

        Process silent = child("sleep");
        long silentStart = System.nanoTime();
        RootAccess.Result silentTimeout = run(silent, 350, true);
        check(!silentTimeout.granted && silentTimeout.message.equals("Root 授权请求超时")
                && !silent.isAlive() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - silentStart) < 1_500,
                "silent command wait uses the same bounded deadline");

        AtomicBoolean preInterruptedFlag = new AtomicBoolean();
        AtomicReference<RootAccess.Result> preInterrupted = new AtomicReference<>();
        Thread alreadyCancelled = new Thread(() -> {
            Thread.currentThread().interrupt();
            preInterrupted.set(RootAccess.check());
            preInterruptedFlag.set(Thread.currentThread().isInterrupted());
        }, "root-audit-preinterrupted");
        alreadyCancelled.start();
        alreadyCancelled.join(1_000L);
        check(!alreadyCancelled.isAlive() && preInterrupted.get() != null
                && !preInterrupted.get().granted && preInterruptedFlag.get()
                && preInterrupted.get().message.equals("Root 检测被中断"),
                "already-interrupted caller avoids launching su and retains its interrupt flag");

        Process sleeper = child("sleep");
        AtomicReference<RootAccess.Result> interruptedResult = new AtomicReference<>();
        AtomicReference<Throwable> interruptedError = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                interruptedResult.set(run(sleeper, 4_000, false));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable error) { interruptedError.set(error); }
        }, "root-audit-caller");
        caller.start();
        Thread.sleep(100L);
        caller.interrupt();
        caller.join(1_500L);
        check(!caller.isAlive() && interruptedError.get() == null && !interruptedResult.get().granted
                && interruptRestored.get() && !sleeper.isAlive(),
                "interrupted caller restores flag and leaves no child or reader thread");

        Process parent = child("descendant");
        long holderPid = -1L;
        try {
            long start = System.nanoTime();
            RootAccess.Result result = run(parent, 4_000, false);
            java.util.regex.Matcher pid = java.util.regex.Pattern.compile("holder=(\\d+)").matcher(result.message);
            boolean foundPid = pid.find();
            if (foundPid) holderPid = Long.parseLong(pid.group(1));
            check(result.granted && foundPid && !parent.isAlive()
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000,
                    "real inherited child pipe does not hold the parent runner open");
        } finally {
            if (holderPid > 0) {
                ProcessHandle holder = ProcessHandle.of(holderPid).orElse(null);
                if (holder != null && holder.isAlive()) {
                    holder.destroyForcibly();
                    holder.onExit().get(2, TimeUnit.SECONDS);
                }
            }
            parent.destroyForcibly();
        }
        Set<String> leaked = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("root-audit"))
                .map(Thread::getName).collect(Collectors.toSet());
        check(leaked.isEmpty(), "runner adds no persistent helper threads");
        System.out.println("PASS root process total=" + passed);
    }
}
