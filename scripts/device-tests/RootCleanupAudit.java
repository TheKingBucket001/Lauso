package dev.bucket.launcherslogan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Exercises cleanup failures without invoking su or creating an operating-system process. */
public final class RootCleanupAudit {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }

    private static final class ProbeInput extends ByteArrayInputStream {
        boolean closed;
        boolean failOnClose;
        ProbeInput() { super(new byte[0]); }
        @Override public void close() throws IOException {
            closed = true;
            if (failOnClose) throw new IOException("simulated input close failure");
        }
    }

    private static final class ProbeOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }

    private static final class ProbeProcess extends Process {
        final ProbeInput input = new ProbeInput();
        final ProbeInput error = new ProbeInput();
        final ProbeOutput output = new ProbeOutput();
        boolean alive = true;
        boolean destroyed;
        boolean waited;
        InterruptedException interruption;

        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public boolean isAlive() { return alive; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; alive = false; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public int waitFor() throws InterruptedException {
            waitFor(0, TimeUnit.MILLISECONDS);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waited = true;
            if (interruption != null) throw interruption;
            return true;
        }
        boolean allStreamsClosed() { return input.closed && error.closed && output.closed; }
    }

    public static void main(String[] args) throws Exception {
        Method cleanup = RootAccess.class.getDeclaredMethod("closeProcess", Process.class);
        cleanup.setAccessible(true);

        ProbeProcess normal = new ProbeProcess();
        cleanup.invoke(null, normal);
        check(normal.destroyed && normal.waited && normal.allStreamsClosed(),
                "normal cleanup destroys process and closes all streams");

        ProbeProcess exited = new ProbeProcess();
        exited.alive = false;
        cleanup.invoke(null, exited);
        check(!exited.destroyed && !exited.waited && exited.allStreamsClosed(),
                "exited process closes streams without another wait");

        ProbeProcess interrupted = new ProbeProcess();
        InterruptedException expected = new InterruptedException("simulated wait interruption");
        interrupted.interruption = expected;
        interrupted.error.failOnClose = true;
        Throwable actual = null;
        try {
            cleanup.invoke(null, interrupted);
        } catch (InvocationTargetException failure) {
            actual = failure.getCause();
        }
        check(actual == expected && interrupted.destroyed && interrupted.waited
                        && interrupted.allStreamsClosed(),
                "interrupted wait preserves original exception and closes all streams despite IOException");

        ProbeProcess closeFailure = new ProbeProcess();
        closeFailure.input.failOnClose = true;
        cleanup.invoke(null, closeFailure);
        check(closeFailure.destroyed && closeFailure.waited && closeFailure.allStreamsClosed(),
                "normal cleanup still closes every stream when one close fails");
        System.out.println("PASS root cleanup total=4");
    }
}
