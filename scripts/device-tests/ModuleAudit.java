package dev.bucket.launcherslogan;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.regex.Pattern;

import dev.bucket.launcherslogan.hook.LauncherSloganModule;

/** Tests isolated View trees and read-only Root commands; does not change user settings. */
public final class ModuleAudit {
    private static final int TEXT_ID = 12345;
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = LauncherSloganModule.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    @SuppressWarnings("unchecked")
    private static Map<View, Object> state(String name) throws Exception {
        Field field = LauncherSloganModule.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<View, Object>) field.get(null);
    }
    private static FrameLayout row(Context context) {
        FrameLayout row = new FrameLayout(context);
        TextView text = new TextView(context);
        text.setId(TEXT_ID);
        row.addView(text);
        return row;
    }
    private static View find(View root, boolean last) throws Exception {
        return (View) call("findNativeRow", new Class<?>[]{View.class, int.class, boolean.class}, root, TEXT_ID, last);
    }
    private static boolean isRow(View view) throws Exception {
        return (Boolean) call("isShortcutRow", new Class<?>[]{View.class, int.class}, view, TEXT_ID);
    }
    private static Object guard(FrameLayout container, Context context) throws Exception {
        Class<?> type = Class.forName(LauncherSloganModule.class.getName() + "$PopupMaterialGuard");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                ViewGroup.class, ViewTreeObserver.class, Context.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object guard = constructor.newInstance(container, container.getViewTreeObserver(), context, false, false);
        state("MATERIAL_PRE_DRAW_GUARDS").put(container, guard);
        Method attach = type.getDeclaredMethod("attach");
        attach.setAccessible(true);
        attach.invoke(guard);
        return guard;
    }
    private static boolean hasPreDrawListeners(ViewTreeObserver observer) throws Exception {
        Method hasListeners = ViewTreeObserver.class.getDeclaredMethod("hasOnPreDrawListeners");
        hasListeners.setAccessible(true);
        return (Boolean) hasListeners.invoke(observer);
    }
    private static boolean guardReleased(Object guard) throws Exception {
        for (String name : new String[]{"context", "observer"}) {
            Field field = guard.getClass().getDeclaredField(name);
            field.setAccessible(true);
            if (field.get(guard) != null) return false;
        }
        return true;
    }
    private static void auditGuard(Context context) throws Exception {
        FrameLayout container = new FrameLayout(context);
        ViewTreeObserver floating = container.getViewTreeObserver();
        Object guard = guard(container, context);
        check(hasPreDrawListeners(floating), "material guard registers a pre-draw listener");

        // Exercise Android's real listener transfer, then the matching attach/detach callbacks.
        // This does not pretend that these isolated View trees have a real Window.
        FrameLayout windowOwner = new FrameLayout(context);
        ViewTreeObserver windowObserver = windowOwner.getViewTreeObserver();
        Method merge = ViewTreeObserver.class.getDeclaredMethod("merge", ViewTreeObserver.class);
        merge.setAccessible(true);
        merge.invoke(windowObserver, floating);
        ((View.OnAttachStateChangeListener) guard).onViewAttachedToWindow(windowOwner);
        check(!floating.isAlive() && hasPreDrawListeners(windowObserver),
                "floating observer transfers its guard to the window observer");
        ((View.OnAttachStateChangeListener) guard).onViewDetachedFromWindow(container);
        check(!hasPreDrawListeners(windowObserver)
                        && !state("MATERIAL_PRE_DRAW_GUARDS").containsKey(container) && guardReleased(guard),
                "detach before the first frame removes the transferred listener and releases state");
        check(((ViewTreeObserver.OnPreDrawListener) guard).onPreDraw()
                        && !hasPreDrawListeners(windowObserver),
                "retired guard stays inert on a stale pre-draw callback");

        FrameLayout expired = new FrameLayout(context) {
            @Override public boolean isAttachedToWindow() { return true; }
        };
        Object expiredGuard = guard(expired, context);
        state("ORIGINAL_CONTAINER_BACKGROUNDS").put(expired, null);
        Field expiresAt = expiredGuard.getClass().getDeclaredField("expiresAtMs");
        expiresAt.setAccessible(true);
        expiresAt.setLong(expiredGuard, 0L);
        ((ViewTreeObserver.OnPreDrawListener) expiredGuard).onPreDraw();
        check(!state("APPLIED_PANEL_BACKGROUNDS").containsKey(expired)
                        && !state("MATERIAL_PRE_DRAW_GUARDS").containsKey(expired)
                        && !hasPreDrawListeners(expired.getViewTreeObserver()) && guardReleased(expiredGuard),
                "expired guard retires before rebuilding any material");
        state("ORIGINAL_CONTAINER_BACKGROUNDS").remove(expired);

        class FailingFrame extends FrameLayout {
            boolean fail;
            int failedReads;
            FailingFrame() { super(context); }
            @Override public boolean isAttachedToWindow() { return true; }
            @Override public Drawable getBackground() {
                if (fail) {
                    failedReads++;
                    throw new IllegalStateException("expected material guard audit failure");
                }
                return super.getBackground();
            }
        }
        FailingFrame failing = new FailingFrame();
        Object failedGuard = guard(failing, context);
        expiresAt.setLong(failedGuard, Long.MAX_VALUE);
        state("ORIGINAL_CONTAINER_BACKGROUNDS").put(failing, null);
        failing.fail = true;
        ((ViewTreeObserver.OnPreDrawListener) failedGuard).onPreDraw();
        ((ViewTreeObserver.OnPreDrawListener) failedGuard).onPreDraw();
        check(failing.failedReads == 1 && guardReleased(failedGuard)
                        && !hasPreDrawListeners(failing.getViewTreeObserver())
                        && !state("MATERIAL_PRE_DRAW_GUARDS").containsKey(failing),
                "material failure retires the guard and cannot repeat on later frames");
        failing.fail = false;
        call("restorePanelBackground", new Class<?>[]{View.class}, failing);
    }
    public static void main(String[] args) throws Exception {
        RuleStoreAudit.main(args);
        RootCleanupAudit.main(args);
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Constructor<?> constructor = thread.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object activityThread = constructor.newInstance();
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        FrameLayout root = new FrameLayout(context);
        FrameLayout slogan = row(context);
        slogan.setTag("dev.bucket.launcherslogan.row");
        FrameLayout first = row(context);
        FrameLayout structural = new FrameLayout(context);
        FrameLayout last = row(context);
        root.addView(slogan);
        root.addView(first);
        root.addView(structural);
        structural.addView(last);
        check(find(root, false) == first && find(root, true) == last, "native row order and slogan exclusion");
        FrameLayout nested = row(context);
        last.addView(nested);
        check(find(root, true) == nested && find(last, false) == last, "nested row traversal preserves front/back ordering");
        last.removeView(nested);
        check(isRow(slogan) && isRow(first) && !isRow(structural)
                && !isRow(new View(context)), "row detection excludes wrappers and leaf views");

        Drawable panel = new ColorDrawable(Color.GRAY);
        Drawable wrapper = new ColorDrawable(Color.WHITE);
        Drawable rowBackground = new ColorDrawable(Color.BLUE);
        Drawable rowForeground = new ColorDrawable(Color.RED);
        ViewOutlineProvider outline = ViewOutlineProvider.BACKGROUND;
        state("ORIGINAL_CONTAINER_BACKGROUNDS").put(root, panel);
        state("ORIGINAL_CONTAINER_ELEVATIONS").put(root, 7f);
        state("ORIGINAL_CONTAINER_CLIP").put(root, false);
        state("ORIGINAL_CONTAINER_OUTLINES").put(root, outline);
        state("ORIGINAL_STRUCTURAL_BACKGROUNDS").put(structural, wrapper);
        state("ORIGINAL_STRUCTURAL_ELEVATIONS").put(structural, 4f);
        first.setBackground(rowBackground);
        first.setForeground(rowForeground);
        call("applyShortcutRowRipple", new Class<?>[]{View.class, int.class}, first, 3);
        check(first.getForeground() instanceof RippleDrawable
                && ((ColorDrawable) first.getBackground()).getColor() == Color.TRANSPARENT,
                "ripple uses transparent idle background");
        root.setBackgroundColor(Color.BLACK);
        root.setElevation(0f);
        root.setClipToOutline(true);
        structural.setBackgroundColor(Color.TRANSPARENT);
        structural.setElevation(0f);
        call("restorePanelBackground", new Class<?>[]{View.class}, root);
        check(root.getBackground() == panel && root.getElevation() == 7f
                && !root.getClipToOutline() && root.getOutlineProvider() == outline,
                "panel background elevation clipping and outline restored");
        check(structural.getBackground() == wrapper && structural.getElevation() == 4f,
                "structural background and elevation restored");
        check(first.getBackground() == rowBackground && first.getForeground() == rowForeground,
                "row background and foreground restored");
        String[] caches = {"ORIGINAL_CONTAINER_BACKGROUNDS", "ORIGINAL_CONTAINER_ELEVATIONS",
                "ORIGINAL_CONTAINER_CLIP", "ORIGINAL_CONTAINER_OUTLINES", "ORIGINAL_ROW_BACKGROUNDS",
                "ORIGINAL_ROW_FOREGROUNDS", "APPLIED_ROW_FOREGROUNDS", "APPLIED_ROW_RIPPLE_TOP_INSETS",
                "ORIGINAL_STRUCTURAL_BACKGROUNDS", "ORIGINAL_STRUCTURAL_ELEVATIONS", "APPLIED_PANEL_BACKGROUNDS"};
        for (String cache : caches) check(state(cache).isEmpty(), "cache cleared " + cache);
        call("restorePanelBackground", new Class<?>[]{View.class}, root);
        check(root.getBackground() == panel && first.getForeground() == rowForeground,
                "cleanup is idempotent");
        auditGuard(context);

        Field rootId = RootAccess.class.getDeclaredField("ROOT_ID");
        rootId.setAccessible(true);
        Pattern pattern = (Pattern) rootId.get(null);
        check(pattern.matcher("uid=0(root) gid=0(root)").find()
                && !pattern.matcher("notuid=0").find() && !pattern.matcher("uid=1000").find()
                && !pattern.matcher("uid=0evil").find(), "root identity cannot match a partial uid");
        RootAccess.Result identity = RootAccess.check();
        check(identity.granted && identity.exitCode == 0, "unified Root runner verifies actual uid 0");
        Method run = RootAccess.class.getDeclaredMethod("runWithSu", String.class);
        run.setAccessible(true);
        RootAccess.Result failed = (RootAccess.Result) run.invoke(null, "exit 7");
        check(!failed.granted && failed.exitCode == 7, "Root runner propagates command failure");
        long start = System.nanoTime();
        RootAccess.Result timeout = (RootAccess.Result) run.invoke(null, "sleep 6");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        check(!timeout.granted && timeout.exitCode == -1 && elapsedMs < 5_500,
                "Root command timeout bounded elapsedMs=" + elapsedMs);
        check(!RootAccess.saveMenuContent("not-an-option", true).granted,
                "unknown settings rejected before Root invocation");
        System.out.println("PASS module total=" + passed);
    }
}
