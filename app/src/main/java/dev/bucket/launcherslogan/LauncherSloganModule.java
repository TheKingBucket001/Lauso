package dev.bucket.launcherslogan;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.animation.AnimatorSet;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class LauncherSloganModule extends XposedModule {
    private static final String TAG = "LauncherSlogan";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher";
    private static final String POPUP_CLASS =
            "com.android.launcher3.popup.OplusPopupContainerWithArrow";
    private static final String ROW_TAG = "dev.bucket.launcherslogan.row";
    // The normal-menu row is laid out once before opening. Retain each native row's original
    // margin so the slogan may absorb its preceding divider reserve without moving the panel.
    private static final WeakHashMap<View, Integer> NORMAL_NATIVE_TOP_MARGINS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COMPACT_NATIVE_GROUP_TOP_MARGINS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COMPACT_HIDDEN_VISIBILITIES =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Drawable> ORIGINAL_CONTAINER_BACKGROUNDS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Float> ORIGINAL_CONTAINER_ELEVATIONS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> ORIGINAL_CONTAINER_CLIP =
            new WeakHashMap<>();
    private static final WeakHashMap<View, ViewOutlineProvider> ORIGINAL_CONTAINER_OUTLINES =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Drawable> ORIGINAL_ROW_BACKGROUNDS =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, Float> ORIGINAL_BLUR_ALPHA =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> ORIGINAL_ADD_BLUR =
            new WeakHashMap<>();
    // Compact geometry can be finalized more than once for the same popup instance. Keep the
    // native row's baseline values so the bottom inset is applied exactly once.
    private static final WeakHashMap<View, Integer> COMPACT_NATIVE_ORIGINAL_HEIGHTS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Float> COMPACT_NATIVE_ORIGINAL_ICON_TRANSLATIONS =
            new WeakHashMap<>();
    // Measured against the supplied iPhone context-menu reference, not the ColorOS template.
    private static final int IPHONE_MENU_WIDTH_DP = 160;
    private static final int IPHONE_SINGLE_ROW_DP = 32;
    private static final float IPHONE_TEXT_SP = 11f;
    private static final int IPHONE_SLOGAN_TOP_PADDING_DP = 6;
    private static final int IPHONE_SLOGAN_BOTTOM_PADDING_DP = 5;
    // The native row's CENTER_VERTICAL gravity aligns view boxes. On the target ColorOS font
    // the visible glyph center is 2-5px lower than its icon, so only the text is optically
    // raised by one density-independent pixel. Icons and hit bounds remain native.
    private static final int IPHONE_NATIVE_TEXT_OPTICAL_RAISE_DP = 1;
    // Visual comfort retains 65% of the slogan-to-native text gap, i.e. a 35% reduction.
    // It never changes the slogan font, wrapping, or upper optical padding.
    private static final float VISUAL_COMFORT_GAP_SCALE = 0.65f;
    private static final float VISUAL_COMFORT_RASTER_EDGE_ALLOWANCE_DP = 1.5f;
    private static final int IPHONE_NATIVE_VERTICAL_PADDING_DP = 6;
    // ColorOS regular rows use a much taller optical rhythm than the compact surface.
    // These values are derived from the native row text-gap baseline on the target launcher.
    private static final int NORMAL_SLOGAN_TOP_PADDING_DP = 18;
    private static final int NORMAL_SLOGAN_BOTTOM_PADDING_DP = 14;
    private static final int IPHONE_TEXT_START_DP = 39;
    // The icon consumes the left optical gutter. A smaller trailing inset centers
    // the icon-plus-wrapped-text group inside the panel without moving the shared
    // system icon column.
    private static final int IPHONE_TEXT_END_DP = 16;
    private static final int IPHONE_SYSTEM_ICON_DP = 19;
    private static final int IPHONE_ICON_START_DP = 16;
    // Keep the 19dp icon slot aligned with native rows while making only the marker smaller.
    private static final int SLOGAN_MARKER_INSET_DP = 5;
    private static final int IPHONE_OUTER_VERTICAL_INSET_DP = 2;
    private static final int IPHONE_PANEL_RADIUS_DP = 20;

    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean verificationReceiverInstalled = new AtomicBoolean(false);
    private final AtomicBoolean verificationReceiverRegistrationScheduled = new AtomicBoolean(false);

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!LAUNCHER_PACKAGE.equals(param.getPackageName()) || !hookInstalled.compareAndSet(false, true)) {
            return;
        }

        if (getApiVersion() < 101) {
            log(Log.ERROR, TAG, "Modern API < 101; no hook installed");
            return;
        }

        final Class<?> popupClass;
        try {
            popupClass = Class.forName(POPUP_CLASS, false, param.getClassLoader());
            Method reorderAndShow = popupClass.getDeclaredMethod("reorderAndShow", int.class);
            Method orientAboutObject = popupClass.getMethod("orientAboutObject");
            hook(reorderAndShow).intercept(chain -> {
                Object popup = chain.getThisObject();
                try {
                    addSloganRow(popup);
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Unable to add Launcher menu row", error);
                }
                return chain.proceed();
            });
            hook(orientAboutObject).intercept(chain -> {
                Object popup = chain.getThisObject();
                if (popupClass.isInstance(popup)) {
                    try {
                        // Oplus measures the popup here and uses that result immediately
                        // afterwards to construct the open animator. The slogan row must
                        // already have its final height, rather than requesting a relayout
                        // after the animator has captured the popup geometry.
                        finalizeSloganGeometryBeforeOrient(popup);
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "Unable to prepare slogan before popup measurement", error);
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            hookInstalled.set(false);
            log(Log.ERROR, TAG, "Launcher geometry hook installation failed", error);
            return;
        }

        installOptionalPopupMaterialHooks(popupClass);
        installMenuContentHooks(param.getClassLoader());
        installLauncherVerificationReceiver(param.getClassLoader());
        log(Log.INFO, TAG, "Hook installed for " + POPUP_CLASS + ".reorderAndShow(int)");
    }

    /** Optional material hooks must never block the core pre-measure geometry hook. */
    private void installOptionalPopupMaterialHooks(Class<?> popupClass) {
        try {
            Method onCreateOpenAnimation = popupClass.getDeclaredMethod(
                    "onCreateOpenAnimation", AnimatorSet.class);
            hook(onCreateOpenAnimation).intercept(chain -> {
                try {
                    stabilizePopupMaterial(chain.getThisObject());
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "Unable to prepare popup material", error);
                }
                Object result = chain.proceed();
                try {
                    // Oplus starts this AnimatorSet only after this callback returns. Its own
                    // method may reset the first divider, so restore it after that work.
                    restoreNormalNativeDividersBeforeOpenAnimation(chain.getThisObject());
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "Unable to restore regular-menu divider", error);
                }
                return result;
            });
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Open material hook unavailable", error);
        }

        try {
            Method onCreateCloseAnimation = popupClass.getDeclaredMethod(
                    "onCreateCloseAnimation", AnimatorSet.class);
            hook(onCreateCloseAnimation).intercept(chain -> {
                try {
                    // getOpenCloseAnimator(false, ...) restores popup_container_background
                    // immediately before this callback. Restore the chosen panel before the
                    // first close-animation frame is scheduled.
                    stabilizePopupMaterial(chain.getThisObject());
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "Unable to keep popup material during close", error);
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Close material hook unavailable", error);
        }
    }

    /** Installs only the documented ColorOS menu visibility hooks in the Launcher process. */
    private void installMenuContentHooks(ClassLoader classLoader) {
        final String shortcutBase = "com.android.launcher3.popup.OplusBaseSystemShortcut$";
        installExtraVisibilityHook(classLoader, shortcutBase + "PrivacyLock",
                MenuContentSettings.HIDE_PRIVACY_LOCK);
        installExtraVisibilityHook(classLoader, shortcutBase + "ClosePrivacyLock",
                MenuContentSettings.HIDE_CLOSE_PRIVACY_LOCK);
        installExtraVisibilityHook(classLoader, shortcutBase + "OplusAppInfo",
                MenuContentSettings.HIDE_APP_INFO);
        installExtraVisibilityHook(classLoader, shortcutBase + "AppShare",
                MenuContentSettings.HIDE_APP_SHARE);
        installExtraVisibilityHook(classLoader, shortcutBase + "AppEdit",
                MenuContentSettings.HIDE_APP_EDIT);
        installExtraVisibilityHook(classLoader, shortcutBase + "CardAdd",
                MenuContentSettings.HIDE_SERVICE_CARD);
        installMoreFlatteningHooks(classLoader, shortcutBase);
        installDeepShortcutHooks(classLoader);
    }

    private void installExtraVisibilityHook(ClassLoader classLoader, String className, String option) {
        try {
            Class<?> shortcutClass = Class.forName(className, false, classLoader);
            Method isEnabled = shortcutClass.getDeclaredMethod("isEnabled");
            hook(isEnabled).intercept(chain -> {
                Context context = shortcutContext(chain.getThisObject());
                if (context != null && MenuContentSettings.hidesSystemItem(context, option)) {
                    return false;
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Menu visibility hook unavailable for " + className, error);
        }
    }

    private void installMoreFlatteningHooks(ClassLoader classLoader, String shortcutBase) {
        try {
            Class<?> moreClass = Class.forName(shortcutBase + "MordFunctions", false, classLoader);
            Method isEnabled = moreClass.getDeclaredMethod("isEnabled");
            hook(isEnabled).intercept(chain -> {
                Context context = shortcutContext(chain.getThisObject());
                if (context != null && MenuContentSettings.read(
                        context, MenuContentSettings.DISABLE_LONG_PRESS_MENU)) {
                    return false;
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            log(Log.WARN, TAG, "More-functions visibility hook unavailable", error);
        }

        // ColorOS suppresses direct entries whenever a deep shortcut exists unless the
        // shortcut instance is marked as a more-menu item. Reusing that native flag lets
        // the existing container place the entries without deleting views after layout.
        final String[] directEntries = {
                "PrivacyLock",
                "ClosePrivacyLock",
                "OplusAppInfo",
                "AppShare",
                "AppEdit",
        };
        try {
            Class<?> activityClass = Class.forName(
                    "com.android.launcher3.BaseDraggingActivity", false, classLoader);
            Class<?> itemInfoClass = Class.forName(
                    "com.android.launcher3.model.data.ItemInfo", false, classLoader);
            for (String entry : directEntries) {
                Class<?> entryClass = Class.forName(shortcutBase + entry, false, classLoader);
                Constructor<?> constructor = entryClass.getDeclaredConstructor(
                        activityClass, itemInfoClass, View.class);
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object shortcut = chain.getThisObject();
                    Context context = shortcutContext(shortcut);
                    if (context != null && MenuContentSettings.read(
                            context, MenuContentSettings.DISABLE_LONG_PRESS_MENU)) {
                        writeField(shortcut, "mMoreShortcut", true);
                    }
                    return result;
                });
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "More-functions flattening hooks unavailable", error);
        }
    }

    private void installDeepShortcutHooks(ClassLoader classLoader) {
        try {
            Class<?> itemInfoClass = Class.forName(
                    "com.android.launcher3.model.data.ItemInfo", false, classLoader);
            Class<?> providerClass = Class.forName(
                    "com.android.launcher3.popup.PopupDataProvider", false, classLoader);
            Method countForItem = providerClass.getDeclaredMethod(
                    "getShortcutCountForItem", itemInfoClass);
            Method countForContext = providerClass.getDeclaredMethod(
                    "getShortcutCountForItem", Context.class, itemInfoClass);
            hook(countForItem).intercept(chain -> hideDeepShortcuts()
                    ? 0 : chain.proceed());
            hook(countForContext).intercept(chain -> hideDeepShortcuts()
                    ? 0 : chain.proceed());

            Class<?> populatorClass = Class.forName(
                    "com.android.launcher3.popup.PopupPopulator", false, classLoader);
            Method shortcutList = populatorClass.getDeclaredMethod(
                    "getShortcutInfoList", Context.class, itemInfoClass);
            hook(shortcutList).intercept(chain -> hideDeepShortcuts()
                    ? Collections.emptyList() : chain.proceed());
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Deep-shortcut visibility hooks unavailable", error);
        }
    }

    private static boolean hideDeepShortcuts() {
        Context context = launcherApplicationContext();
        return context != null && MenuContentSettings.read(
                context, MenuContentSettings.HIDE_DEEP_SHORTCUTS);
    }

    private static Context shortcutContext(Object shortcut) {
        Object target = readFieldUnchecked(shortcut, "mTarget");
        return target instanceof Context ? (Context) target : launcherApplicationContext();
    }

    private static Context launcherApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void addSloganRow(Object popup) throws Exception {
        View longPressedView = (View) readField(popup, "mLongPressedView");
        String targetPackage = longPressedView == null ? null : targetPackage(longPressedView.getTag());
        if (targetPackage == null) {
            return;
        }

        ViewGroup shortcuts = (ViewGroup) readField(popup, "mPopupShortcutContainer");
        if (shortcuts == null || shortcuts.findViewWithTag(ROW_TAG) != null) {
            return;
        }

        Context context = shortcuts.getContext();
        SloganRule rule = RuleStore.find(context, targetPackage);
        if (rule == null) {
            return;
        }
        int layoutId = context.getResources().getIdentifier(
                "oplus_deep_shortcut", "layout", LAUNCHER_PACKAGE);
        int textId = context.getResources().getIdentifier(
                "bubble_text", "id", LAUNCHER_PACKAGE);
        int iconId = context.getResources().getIdentifier("icon", "id", LAUNCHER_PACKAGE);
        if (layoutId == 0 || textId == 0 || iconId == 0) {
            log(Log.WARN, TAG, "Launcher menu resource IDs were not found");
            return;
        }

        View row = LayoutInflater.from(context).inflate(layoutId, shortcuts, false);
        TextView text = row.findViewById(textId);
        View icon = row.findViewById(iconId);
        if (text == null || icon == null) {
            log(Log.WARN, TAG, "Launcher menu row did not contain text or icon");
            return;
        }

        boolean compact = MenuMaterialSettings.readCompact(context);
        configureMenuText(text, rule, compact);
        // The title and subtitle are semantic fields. Allow them to wrap completely,
        // then derive the row height from the measured text instead of truncating it.
        text.setMaxLines(Integer.MAX_VALUE);
        text.setEllipsize(null);
        text.setSingleLine(false);
        text.setHorizontallyScrolling(false);
        text.setMinLines(1);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(0f, 1f);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        // The ColorOS row template inflates bubble_text as MATCH_PARENT because system
        // rows are fixed-height. A slogan row is content-height, so retain only the
        // actual text block and center that block in the row.
        ViewGroup.LayoutParams textParams = text.getLayoutParams();
        if (textParams != null) {
            textParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (textParams instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) textParams).gravity =
                        Gravity.START | Gravity.CENTER_VERTICAL;
            }
            text.setLayoutParams(textParams);
        }
        text.setContentDescription(contentDescription(rule));
        // Keep this slot on the same horizontal centerline as Launcher's system icons.
        // The compact pass gives every icon the same 19dp slot; this inset then makes
        // the custom marker deliberately smaller without shifting it to the right.
        icon.setBackground(smallCircle(context, Color.BLACK, SLOGAN_MARKER_INSET_DP));
        // The custom row is the first item, so its own top divider would be above the
        // slogan. The boundary belongs to the first native system row below it.
        setDividerVisible(row, false);

        row.setTag(ROW_TAG);
        row.setContentDescription(contentDescription(rule));
        row.setOnClickListener(clicked -> {
            // Let ColorOS consume the same Back event as a normal user cancellation. The
            // native path owns the stretch/drag animation and clears its private overlays;
            // manually hiding those views leaves the smaller white resize ring behind.
            boolean backInjected = injectFastBackKey();
            if (!backInjected) {
                closePopup(popup);
            } else {
                // A few firmware builds accept the injected event but keep this popup alive
                // for one frame. Close only the Launcher popup as a local fallback; the Back
                // event itself has already been delivered to the currently focused window.
                View fallbackAnchor = popup instanceof View ? (View) popup : longPressedView;
                if (fallbackAnchor != null) {
                    fallbackAnchor.postDelayed(() -> {
                        if (isPopupStillVisible(popup)) {
                            closePopup(popup);
                        }
                    }, 48L);
                }
            }
            String message = rule.getToastMessage();
            if (!message.isEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
        shortcuts.addView(row, 0);
    }

    private static void applyPanelBackground(Object popup, boolean translucent) {
        Object value = readFieldUnchecked(popup, "mAllPopupShortcutContainer");
        if (!(value instanceof View)) return;
        View container = (View) value;
        if (!translucent) {
            restorePanelBackground(container);
            return;
        }
        if (!ORIGINAL_CONTAINER_BACKGROUNDS.containsKey(container)) {
            ORIGINAL_CONTAINER_BACKGROUNDS.put(container, container.getBackground());
            ORIGINAL_CONTAINER_ELEVATIONS.put(container, container.getElevation());
            ORIGINAL_CONTAINER_CLIP.put(container, container.getClipToOutline());
            ORIGINAL_CONTAINER_OUTLINES.put(container, container.getOutlineProvider());
        }
        GradientDrawable material = new GradientDrawable();
        material.setShape(GradientDrawable.RECTANGLE);
        // Keep the home screen sharp by default. The alpha lets the wallpaper tint the
        // surface slightly, matching the iOS material without a full-screen blur layer.
        material.setColor(Color.argb(228, 250, 251, 255));
        material.setCornerRadius(dp(container.getContext(), IPHONE_PANEL_RADIUS_DP));
        container.setBackground(material);
        container.setClipToOutline(true);
        container.setOutlineProvider(roundOutline(container, IPHONE_PANEL_RADIUS_DP));
        container.setElevation(dp(container.getContext(), 10));
        if (container instanceof ViewGroup) {
            makeShortcutRowsTransparent((ViewGroup) container, container.getContext());
        }
    }

    private static void restorePanelBackground(View container) {
        if (ORIGINAL_CONTAINER_BACKGROUNDS.containsKey(container)) {
            container.setBackground(ORIGINAL_CONTAINER_BACKGROUNDS.get(container));
            container.setElevation(ORIGINAL_CONTAINER_ELEVATIONS.getOrDefault(container, 0f));
            container.setClipToOutline(ORIGINAL_CONTAINER_CLIP.getOrDefault(container, false));
            container.setOutlineProvider(ORIGINAL_CONTAINER_OUTLINES.get(container));
        }
        if (container instanceof ViewGroup) restoreShortcutRows((ViewGroup) container);
    }

    private static void restoreShortcutRows(ViewGroup group) {
        int textId = group.getContext().getResources().getIdentifier(
                "bubble_text", "id", LAUNCHER_PACKAGE);
        if (isShortcutRow(group, textId) && ORIGINAL_ROW_BACKGROUNDS.containsKey(group)) {
            group.setBackground(ORIGINAL_ROW_BACKGROUNDS.get(group));
        }
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ViewGroup) restoreShortcutRows((ViewGroup) child);
        }
    }

    /**
     * Keeps the visible panel and the system blur lifecycle in one coherent state. Oplus
     * reconstructs the native white panel just before close animation creation, so this must
     * be called from both animation callbacks as well as the initial menu setup.
     */
    private static void stabilizePopupMaterial(Object popup) {
        Context context = (Context) readFieldUnchecked(popup, "mContext");
        if (context == null) {
            return;
        }
        preparePopupBackdrop(popup);
        applyPanelBackground(popup, MenuMaterialSettings.readPanel(context));
    }

    private static void preparePopupBackdrop(Object popup) {
        Context context = (Context) readFieldUnchecked(popup, "mContext");
        if (context == null) {
            return;
        }
        if (!MenuMaterialSettings.readDisableBackdrop(context)) {
            restorePopupBackdrop(popup);
            return;
        }
        // The switch controls the native full-screen ColorOS layer. It is deliberately
        // disabled by default; the menu surface itself remains translucent above the
        // sharp launcher wallpaper.
        disablePopupBackdrop(popup);
    }

    private static void makeShortcutRowsTransparent(ViewGroup outer, Context context) {
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        View firstNativeRow = MenuMaterialSettings.readCompact(context)
                && MenuMaterialSettings.readVisualComfort(context)
                ? findFirstNativeRow(outer, context) : null;
        int firstNativeTopInset = compactNativeGroupOverlap(firstNativeRow);
        makeShortcutRowsTransparent(outer, textId, firstNativeRow, firstNativeTopInset);
    }

    private static void makeShortcutRowsTransparent(
            View view, int textId, View firstNativeRow, int firstNativeTopInset) {
        if (isShortcutRow(view, textId)) {
            applyShortcutRowRipple(view, view == firstNativeRow ? firstNativeTopInset : 0);
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            makeShortcutRowsTransparent(group.getChildAt(index), textId,
                    firstNativeRow, firstNativeTopInset);
        }
    }

    private static int compactNativeGroupOverlap(View firstNativeRow) {
        Object parent = firstNativeRow == null ? null : firstNativeRow.getParent();
        if (!(parent instanceof View)) {
            return 0;
        }
        ViewGroup.LayoutParams params = ((View) parent).getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return 0;
        }
        return Math.max(0, -((ViewGroup.MarginLayoutParams) params).topMargin);
    }

    private static void applyShortcutRowRipple(View row, int topInset) {
        // A null content and mask create an unbounded ripple that spills across sibling rows.
        // ColorOS also ignores a transparent-content ripple without an opaque mask. The inset
        // on the comfort mode's first native row excludes its layout overlap with the slogan;
        // that overlap is intentionally visual-only and must not react as part of the option.
        if (!ORIGINAL_ROW_BACKGROUNDS.containsKey(row)) {
            ORIGINAL_ROW_BACKGROUNDS.put(row, row.getBackground());
        }
        Drawable mask = topInset > 0
                ? new InsetDrawable(new ColorDrawable(Color.WHITE), 0, topInset, 0, 0)
                : new ColorDrawable(Color.WHITE);
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(52, 47, 116, 200)),
                new ColorDrawable(Color.TRANSPARENT),
                mask));
    }

    private static boolean isShortcutRow(View view, int textId) {
        if (view.getTag() instanceof String && ROW_TAG.equals(view.getTag())) {
            return true;
        }
        String className = view.getClass().getSimpleName();
        if (className.contains("DeepShortcut")) {
            return true;
        }
        if (!(view instanceof ViewGroup) || textId == 0) {
            return false;
        }
        // System rows on this Launcher are FrameLayout subclasses. Their direct children are
        // the label, left icon and divider; containers above them do not own bubble_text.
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getId() == textId) {
                return true;
            }
        }
        return false;
    }

    private static ViewOutlineProvider roundOutline(final View view, final int radiusDp) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View ignored, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(view.getContext(), radiusDp));
            }
        };
    }

    private static void disablePopupBackdrop(Object popup) {
        Object blur = readFieldUnchecked(popup, "mPopBlurView");
        if (blur instanceof View) {
            ORIGINAL_BLUR_ALPHA.putIfAbsent(popup, ((View) blur).getAlpha());
            // finish() also consumes the deferred original-icon callback. Hide only the
            // visual layer so Launcher can complete its normal close lifecycle.
            ((View) blur).setAlpha(0f);
        }
        // Skip just the 0 -> 1 opening blur animation. Keep mPopBlurView and
        // mRemovePopupBlurView intact for the native close animation and cleanup.
        if (!ORIGINAL_ADD_BLUR.containsKey(popup)) {
            ORIGINAL_ADD_BLUR.put(popup, readBooleanField(popup, "mAddPopupBlurView", true));
        }
        writeField(popup, "mAddPopupBlurView", false);
    }

    private static void restorePopupBackdrop(Object popup) {
        Object blur = readFieldUnchecked(popup, "mPopBlurView");
        Float alpha = ORIGINAL_BLUR_ALPHA.get(popup);
        if (blur instanceof View && alpha != null) ((View) blur).setAlpha(alpha);
        if (ORIGINAL_ADD_BLUR.containsKey(popup)) {
            writeField(popup, "mAddPopupBlurView", ORIGINAL_ADD_BLUR.get(popup));
        }
    }

    /** Applies compact geometry to every popup, including menus without a LauSo row. */
    private static void applyCompactGeometry(Object popup, Context context) {
        Object value = readFieldUnchecked(popup, "mAllPopupShortcutContainer");
        if (!(value instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) value;
        Object shortcutsValue = readFieldUnchecked(popup, "mPopupShortcutContainer");
        boolean hasSlogan = shortcutsValue instanceof ViewGroup
                && ((ViewGroup) shortcutsValue).findViewWithTag(ROW_TAG) != null;
        int width = dp(context, IPHONE_MENU_WIDTH_DP);
        // A configured slogan owns the panel's top optical inset so its ripple reaches
        // the rounded panel edge. Menus without a slogan retain the native inset.
        int outerInset = dp(context, IPHONE_OUTER_VERTICAL_INSET_DP);
        // The last native row absorbs the lower optical inset so its pressed feedback can
        // reach the panel edge. Keep the total panel height unchanged.
        container.setPadding(container.getPaddingLeft(), hasSlogan ? 0 : outerInset,
                container.getPaddingRight(), 0);
        setWidth(container, width);
        resizePopupTree(container, context, width, true);
        extendLastCompactNativeRow(container, context, outerInset);
        hideCompactDividers(context, container);
    }

    /**
     * The only geometry owner for a menu opening. This is called before Oplus takes the
     * measurement used for positioning and animation pivots, so no animation-frame repair
     * is required afterwards.
     */
    private static void finalizeSloganGeometryBeforeOrient(Object popup) {
        Context context = (Context) readFieldUnchecked(popup, "mContext");
        if (context == null) {
            return;
        }
        boolean compact = MenuMaterialSettings.readCompact(context);
        boolean visualComfort = compact && MenuMaterialSettings.readVisualComfort(context);
        ViewGroup shortcuts = (ViewGroup) readFieldUnchecked(popup, "mPopupShortcutContainer");
        View nativeBeforeMode = shortcuts == null ? null : findFirstNativeRow(shortcuts, context);
        restoreNormalNativeTopMargin(nativeBeforeMode);
        restoreCompactNativeGroupTopMargin(nativeBeforeMode);
        if (!compact) {
            Object allContainer = readFieldUnchecked(popup, "mAllPopupShortcutContainer");
            if (allContainer instanceof View) restoreCompactHiddenViews((View) allContainer);
            restoreLastCompactNativeRow(allContainer, context);
        }
        // This is intentionally before the slogan lookup. Compact is a popup-wide
        // preference, so folder and unconfigured-app menus cannot depend on ROW_TAG.
        if (compact) {
            applyCompactGeometry(popup, context);
        }
        if (shortcuts == null) {
            return;
        }
        View sloganRow = shortcuts.findViewWithTag(ROW_TAG);
        if (!(sloganRow instanceof ViewGroup)) {
            return;
        }
        TextView sloganText = sloganRow.findViewById(context.getResources().getIdentifier(
                "bubble_text", "id", LAUNCHER_PACKAGE));
        if (sloganText == null) {
            return;
        }
        View nativeRow = findFirstNativeRow(shortcuts, context);
        TextView nativeText = nativeRow == null ? null : findRowText(nativeRow, context);
        int width = resolveSloganTextWidth(sloganRow, sloganText, shortcuts, context, compact);
        int startPadding = compact ? dp(context, IPHONE_TEXT_START_DP)
                : nativeText == null ? launcherDimension(context,
                        "color_deep_shortcuts_text_padding_start", sloganText.getPaddingStart())
                : nativeText.getPaddingStart();
        int endPadding = compact ? dp(context, IPHONE_TEXT_END_DP)
                : nativeText == null ? launcherDimension(context,
                        "color_popup_padding_end", sloganText.getPaddingEnd())
                : nativeText.getPaddingEnd();
        int compactPanelTopInset = compact
                ? dp(context, IPHONE_OUTER_VERTICAL_INSET_DP) : 0;
        int compactTextTopPadding = dp(context, IPHONE_SLOGAN_TOP_PADDING_DP);
        int topPadding = compact ? compactTextTopPadding + compactPanelTopInset
                : dp(context, NORMAL_SLOGAN_TOP_PADDING_DP);
        // Always measure the compact row with its full, uncompressed padding. Comfort is
        // applied only after the complete text block and its real last line are known.
        int bottomPadding = compact
                ? dp(context, IPHONE_SLOGAN_BOTTOM_PADDING_DP)
                : dp(context, NORMAL_SLOGAN_BOTTOM_PADDING_DP);

        ViewGroup.LayoutParams textParams = sloganText.getLayoutParams();
        if (textParams != null) {
            textParams.width = width;
            textParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (textParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) textParams;
                margins.topMargin = 0;
                margins.bottomMargin = 0;
            }
            if (textParams instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) textParams).gravity =
                        Gravity.START | Gravity.CENTER_VERTICAL;
            }
            sloganText.setLayoutParams(textParams);
        }
        sloganText.setMinHeight(0);
        sloganText.setMinimumHeight(0);
        if (compact && nativeText != null) {
            // Match the Launcher row's font-metric treatment before comparing line
            // heights. Otherwise includeFontPadding differences skew both gaps.
            sloganText.setIncludeFontPadding(nativeText.getIncludeFontPadding());
        }
        sloganText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sloganText.setTranslationY(0f);
        sloganText.setPaddingRelative(startPadding, topPadding, endPadding, bottomPadding);
        sloganText.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        android.text.Layout layout = sloganText.getLayout();
        int layoutHeight = layout == null ? Math.max(0,
                sloganText.getMeasuredHeight() - topPadding - bottomPadding) : layout.getHeight();
        int minimumHeight = compact ? dp(context, IPHONE_SINGLE_ROW_DP)
                : launcherDimension(context, "deep_shortcut_icon_text_item_height", dp(context, 49));
        int targetHeight = Math.max(minimumHeight, layoutHeight + topPadding + bottomPadding);
        if (compact && nativeText != null && nativeRow != null) {
            int nativeRowHeight = dp(context, IPHONE_SINGLE_ROW_DP);
            nativeText.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(nativeRowHeight, View.MeasureSpec.EXACTLY));
            android.text.Layout nativeLayout = nativeText.getLayout();
            int nativeLayoutHeight = nativeLayout == null
                    ? Math.max(0, nativeText.getMeasuredHeight()
                    - nativeText.getPaddingTop() - nativeText.getPaddingBottom())
                    : nativeLayout.getHeight();
            int nativeOpticalInset = Math.max(0, (nativeRowHeight - nativeLayoutHeight) / 2);
            // The compact panel's former top padding now belongs to the slogan row. Keep
            // the same total height and text coordinates while letting this row's ripple
            // start at the panel edge.
            int baseHeight = Math.max(nativeRowHeight,
                    Math.max(layoutHeight + 2 * nativeOpticalInset,
                            layoutHeight + compactTextTopPadding + bottomPadding))
                    + compactPanelTopInset;
            targetHeight = baseHeight;
            int sloganLastBottom = layout == null || layout.getLineCount() == 0
                    ? layoutHeight : layout.getLineBottom(layout.getLineCount() - 1);
            int sloganContentTop = topPadding + Math.max(0,
                    (baseHeight - topPadding - bottomPadding - layoutHeight) / 2);
            int nativeContentTop = nativeText.getPaddingTop() + Math.max(0,
                    (nativeRowHeight - nativeText.getPaddingTop() - nativeText.getPaddingBottom()
                            - nativeLayoutHeight) / 2);

            if (visualComfort) {
                // The user-facing value is the actual text-to-text gap: slogan last line
                // to the first native item line. Layout line boxes include font-leading
                // that is never drawn, so use each line's styled ink bounds and baseline
                // for the target while retaining lineBottom as the no-clipping boundary.
                int sloganLastInkBottom = lineInkBottom(sloganText, layout,
                        layout.getLineCount() - 1);
                int nativeFirstInkTop = lineInkTop(nativeText, nativeLayout, 0);
                // Text bounds exclude the partially covered raster edge that remains visible
                // on screen. Keep a density-scaled allowance in the baseline so the 65% target
                // applies to the user-visible gap rather than only to vector bounds.
                int rasterEdgeAllowance = Math.max(1, Math.round(
                        context.getResources().getDisplayMetrics().density
                                * VISUAL_COMFORT_RASTER_EDGE_ALLOWANCE_DP));
                int originalTextGap = Math.max(0, baseHeight + nativeContentTop
                        + nativeFirstInkTop - sloganContentTop - sloganLastInkBottom
                        + rasterEdgeAllowance);
                int targetTextGap = Math.round(originalTextGap * VISUAL_COMFORT_GAP_SCALE);
                int requestedCut = Math.max(0, originalTextGap - targetTextGap);
                // Visual comfort is strictly lower-gap compression: keep the slogan text
                // and marker fixed, remove only the row's available bottom optical space,
                // and keep the first native row directly below without any view overlap.
                sloganText.setGravity(Gravity.START | Gravity.TOP);
                sloganText.setPaddingRelative(startPadding, sloganContentTop, endPadding, 0);
                targetHeight = baseHeight - requestedCut;
                setCompactNativeGroupTopMargin(nativeRow, 0);

                // The row height is now fixed. Re-measure with EXACTLY and reject the
                // candidate if Android's final Layout would cross the parent clip edge.
                sloganText.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY));
                android.text.Layout checkedLayout = sloganText.getLayout();
                int checkedBottom = sloganContentTop;
                if (checkedLayout != null && checkedLayout.getLineCount() > 0) {
                    checkedBottom += checkedLayout.getLineBottom(checkedLayout.getLineCount() - 1);
                } else {
                    checkedBottom += layoutHeight;
                }
                if (checkedBottom > targetHeight) {
                    // Keep the text fixed. If a rare tall label exhausts the lower space,
                    // release only the unsafe portion of the requested lower-gap cut.
                    targetHeight = checkedBottom;
                    setCompactNativeGroupTopMargin(nativeRow, 0);
                    sloganText.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY));
                }
            } else {
                setCompactNativeGroupTopMargin(nativeRow, 0);
            }
        }

        int normalBoundaryReserve = 0;
        boolean normalBoundaryAssigned = false;
        if (!compact && nativeRow != null) {
            normalBoundaryReserve = normalSloganBoundaryReserve(nativeRow, context);
            normalBoundaryAssigned = offsetFirstNormalNativeRow(nativeRow, normalBoundaryReserve);
        }

        if (textParams != null) {
            textParams.height = targetHeight;
            sloganText.setLayoutParams(textParams);
        }
        ViewGroup.LayoutParams rowParams = sloganRow.getLayoutParams();
        if (rowParams != null) {
            rowParams.height = targetHeight + (normalBoundaryAssigned ? normalBoundaryReserve : 0);
            clearVerticalMargins(rowParams);
            sloganRow.setLayoutParams(rowParams);
        }
        sloganRow.setMinimumHeight(targetHeight
                + (normalBoundaryAssigned ? normalBoundaryReserve : 0));
        if (!compact && nativeRow != null) {
            alignSloganRowWithNativeRow(sloganRow, nativeRow, context);
        }
        // Resolve the final line layout before deriving the marker's optical center.
        // This also makes normal and compact menus use the same post-measure evidence.
        sloganText.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY));
        centerSloganIcon(sloganRow, nativeRow, sloganText, context, compact, targetHeight);
        if (normalBoundaryAssigned) {
            // FrameLayout re-centers its children in the seven-pixel extension. Undo that
            // integer centering shift so the text and marker retain their pre-fix positions.
            int contentOffset = normalBoundaryReserve / 2;
            sloganText.setTranslationY(-contentOffset);
            View sloganIcon = sloganRow.findViewById(context.getResources().getIdentifier(
                    "icon", "id", LAUNCHER_PACKAGE));
            if (sloganIcon != null) {
                sloganIcon.setTranslationY(sloganIcon.getTranslationY() - contentOffset);
            }
        }
    }

    /** Moves every native menu row together only for the overflow beyond the slogan's safe gap. */
    private static void setCompactNativeGroupTopMargin(View nativeRow, int topMargin) {
        Object parent = nativeRow == null ? null : nativeRow.getParent();
        if (!(parent instanceof View)) {
            return;
        }
        View nativeGroup = (View) parent;
        ViewGroup.LayoutParams params = nativeGroup.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (!COMPACT_NATIVE_GROUP_TOP_MARGINS.containsKey(nativeGroup)) {
            COMPACT_NATIVE_GROUP_TOP_MARGINS.put(nativeGroup, margins.topMargin);
        }
        if (margins.topMargin == topMargin) {
            return;
        }
        margins.topMargin = topMargin;
        nativeGroup.setLayoutParams(margins);
    }

    private static void restoreCompactNativeGroupTopMargin(View nativeRow) {
        Object parent = nativeRow == null ? null : nativeRow.getParent();
        if (!(parent instanceof View)) return;
        ViewGroup.LayoutParams params = ((View) parent).getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        Integer original = COMPACT_NATIVE_GROUP_TOP_MARGINS.get((View) parent);
        if (original == null) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        margins.topMargin = original;
        ((View) parent).setLayoutParams(margins);
    }

    /** Lets the final native row own the compact panel's bottom optical inset. */
    private static void extendLastCompactNativeRow(View container, Context context, int extra) {
        if (!(container instanceof ViewGroup) || extra <= 0) return;
        View last = findLastNativeRow(container, context);
        if (last == null) return;
        ViewGroup.LayoutParams params = last.getLayoutParams();
        if (params == null || params.height <= 0) return;
        Integer originalHeight = COMPACT_NATIVE_ORIGINAL_HEIGHTS.get(last);
        if (originalHeight == null) {
            originalHeight = params.height;
            COMPACT_NATIVE_ORIGINAL_HEIGHTS.put(last, originalHeight);
        }
        int targetHeight = originalHeight + extra;
        if (params.height != targetHeight) {
            params.height = targetHeight;
            last.setLayoutParams(params);
        }
        // Keep the existing icon column's screen center while the row absorbs the bottom area.
        View icon = findRowIcon(last, context);
        if (icon != null) {
            Float originalTranslation = COMPACT_NATIVE_ORIGINAL_ICON_TRANSLATIONS.get(icon);
            if (originalTranslation == null) {
                originalTranslation = icon.getTranslationY();
                COMPACT_NATIVE_ORIGINAL_ICON_TRANSLATIONS.put(icon, originalTranslation);
            }
            float targetTranslation = originalTranslation - extra / 2f;
            if (Float.compare(icon.getTranslationY(), targetTranslation) != 0) {
                icon.setTranslationY(targetTranslation);
            }
        }
    }

    private static void restoreLastCompactNativeRow(Object container, Context context) {
        if (!(container instanceof View)) return;
        View last = findLastNativeRow((View) container, context);
        if (last == null) return;
        Integer originalHeight = COMPACT_NATIVE_ORIGINAL_HEIGHTS.get(last);
        if (originalHeight != null) {
            ViewGroup.LayoutParams params = last.getLayoutParams();
            if (params != null && params.height != originalHeight) {
                params.height = originalHeight;
                last.setLayoutParams(params);
            }
        }
        View icon = findRowIcon(last, context);
        if (icon != null) {
            Float originalTranslation = COMPACT_NATIVE_ORIGINAL_ICON_TRANSLATIONS.get(icon);
            if (originalTranslation != null) icon.setTranslationY(originalTranslation);
        }
    }

    private static View findLastNativeRow(View view, Context context) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View result = findLastNativeRow(group.getChildAt(index), context);
                if (result != null) return result;
            }
        }
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        return !ROW_TAG.equals(view.getTag()) && isMenuRowRoot(view, textId) ? view : null;
    }

    /** Returns the top of the visible glyphs relative to a Layout, not its line box. */
    private static int lineInkTop(TextView view, android.text.Layout layout, int line) {
        return layout.getLineBaseline(line) + lineInkBounds(view, layout, line).top;
    }

    /** Returns the bottom of the visible glyphs relative to a Layout, not its line box. */
    private static int lineInkBottom(TextView view, android.text.Layout layout, int line) {
        return layout.getLineBaseline(line) + lineInkBounds(view, layout, line).bottom;
    }

    /**
     * Applies the same MetricAffectingSpan sequence used by the final Layout before measuring
     * the line. This keeps subtitle scaling, CJK glyphs, emoji and bidi text on the optical
     * path without a screen capture or Canvas scan in the Launcher process.
     */
    private static Rect lineInkBounds(TextView view, android.text.Layout layout, int line) {
        CharSequence value = view.getText();
        int start = layout.getLineStart(line);
        int end = layout.getLineVisibleEnd(line);
        Rect result = new Rect();
        boolean hasInk = false;
        String plain = value == null ? "" : value.toString();
        if (start < end && end <= plain.length()) {
            if (value instanceof Spanned) {
                Spanned styled = (Spanned) value;
                for (int runStart = start; runStart < end;) {
                    int runEnd = styled.nextSpanTransition(runStart, end,
                            MetricAffectingSpan.class);
                    if (runEnd <= runStart) {
                        runEnd = Math.min(end, runStart + 1);
                    }
                    TextPaint paint = new TextPaint(view.getPaint());
                    MetricAffectingSpan[] spans = styled.getSpans(runStart,
                            Math.min(runStart + 1, end), MetricAffectingSpan.class);
                    for (MetricAffectingSpan span : spans) {
                        if (styled.getSpanStart(span) <= runStart
                                && styled.getSpanEnd(span) > runStart) {
                            span.updateMeasureState(paint);
                        }
                    }
                    hasInk |= unionTextBounds(result, paint, plain, runStart, runEnd, hasInk);
                    runStart = runEnd;
                }
            } else {
                hasInk = unionTextBounds(result, view.getPaint(), plain, start, end, false);
            }
        }
        if (!hasInk) {
            android.graphics.Paint.FontMetricsInt metrics = view.getPaint().getFontMetricsInt();
            result.set(0, metrics.ascent, 0, metrics.descent);
        }
        return result;
    }

    private static boolean unionTextBounds(
            Rect result, TextPaint paint, String text, int start, int end, boolean hasInk) {
        if (start >= end || text.substring(start, end).trim().isEmpty()) {
            return hasInk;
        }
        Rect bounds = new Rect();
        paint.getTextBounds(text, start, end, bounds);
        if (bounds.isEmpty()) {
            return hasInk;
        }
        if (hasInk) {
            result.union(bounds);
        } else {
            result.set(bounds);
        }
        return true;
    }

    private static int resolveSloganTextWidth(
            View row, TextView text, ViewGroup shortcuts, Context context, boolean compact) {
        if (compact) {
            return dp(context, IPHONE_MENU_WIDTH_DP);
        }
        ViewGroup.LayoutParams textParams = text.getLayoutParams();
        if (textParams != null && textParams.width > 0) {
            return textParams.width;
        }
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        if (rowParams != null && rowParams.width > 0) {
            return rowParams.width;
        }
        if (shortcuts.getWidth() > 0) {
            return shortcuts.getWidth();
        }
        return launcherDimension(context, "deep_shortcut_icon_text_width", dp(context, 232));
    }

    private static int launcherDimension(Context context, String name, int fallback) {
        int resourceId = context.getResources().getIdentifier(name, "dimen", LAUNCHER_PACKAGE);
        return resourceId == 0 ? fallback : context.getResources().getDimensionPixelSize(resourceId);
    }

    private static void centerSloganIcon(
            View row, View nativeRow, TextView sloganText, Context context,
            boolean compact, int rowHeight) {
        View icon = row.findViewById(context.getResources().getIdentifier(
                "icon", "id", LAUNCHER_PACKAGE));
        if (icon != null && icon.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) icon.getLayoutParams();
            View nativeIcon = nativeRow == null ? null : findRowIcon(nativeRow, context);
            if (compact && nativeIcon != null
                    && nativeIcon.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams source =
                        (FrameLayout.LayoutParams) nativeIcon.getLayoutParams();
                iconParams.width = source.width;
                iconParams.height = source.height;
                iconParams.gravity = source.gravity;
                iconParams.setMarginStart(source.getMarginStart());
                iconParams.setMarginEnd(source.getMarginEnd());
                iconParams.leftMargin = source.leftMargin;
                iconParams.rightMargin = source.rightMargin;
            } else {
                iconParams.width = dp(context, IPHONE_SYSTEM_ICON_DP);
                iconParams.height = dp(context, IPHONE_SYSTEM_ICON_DP);
            }
            iconParams.topMargin = 0;
            iconParams.bottomMargin = 0;
            icon.setLayoutParams(iconParams);
            icon.setBackground(smallCircle(context, Color.BLACK, SLOGAN_MARKER_INSET_DP));
            // The marker remains in the same x slot as the native icon. Its y position
            // follows the measured text block, rather than the full row, so a large title
            // plus a smaller subtitle looks centered in both compact and regular menus.
            icon.setTranslationY(sloganTextCenterOffset(sloganText, rowHeight));
        }
    }

    /** Returns the offset from the row center to the final text Layout's optical block center. */
    private static float sloganTextCenterOffset(TextView text, int rowHeight) {
        android.text.Layout layout = text.getLayout();
        if (layout == null || layout.getLineCount() == 0 || rowHeight <= 0) {
            return 0f;
        }
        int layoutHeight = layout.getHeight();
        int contentTop = text.getPaddingTop();
        int verticalGravity = text.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (verticalGravity == Gravity.CENTER_VERTICAL) {
            int contentHeight = Math.max(0, rowHeight - text.getPaddingTop()
                    - text.getPaddingBottom());
            contentTop += Math.max(0, (contentHeight - layoutHeight) / 2);
        } else if (verticalGravity == Gravity.BOTTOM) {
            contentTop = Math.max(text.getPaddingTop(), rowHeight - text.getPaddingBottom()
                    - layoutHeight);
        }
        return contentTop + layoutHeight / 2f - rowHeight / 2f;
    }

    /** Restores the stock ColorOS separator on every real system row in a regular menu. */
    private static void restoreNormalNativeDividersBeforeOpenAnimation(Object popup) {
        Context context = (Context) readFieldUnchecked(popup, "mContext");
        if (context == null || MenuMaterialSettings.readCompact(context)) {
            return;
        }
        Object value = readFieldUnchecked(popup, "mPopupShortcutContainer");
        if (value instanceof ViewGroup) {
            restoreNormalNativeDividers((ViewGroup) value, context);
        }
    }

    /** Restores the stock ColorOS separator on every real system row in a regular menu. */
    private static void restoreNormalNativeDividers(ViewGroup shortcuts, Context context) {
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        restoreNormalNativeDividerVisibility(shortcuts, textId);
        View firstNativeRow = findFirstNativeRow(shortcuts, context);
        if (firstNativeRow != null) {
            ensureNormalBoundaryDivider(firstNativeRow, shortcuts, context);
        }
    }

    private static void restoreNormalNativeDividerVisibility(View view, int textId) {
        if (!ROW_TAG.equals(view.getTag()) && isMenuRowRoot(view, textId)) {
            // The first system row normally suppresses its own top divider because it is
            // the first item. After the LauSo row is inserted, that edge becomes the
            // slogan/system boundary and must use the launcher-provided divider.
            setDividerVisible(view, true);
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            restoreNormalNativeDividerVisibility(group.getChildAt(index), textId);
        }
    }

    /**
     * The first native row owns the same ColorOS divider child as later rows, but starts
     * hidden because it was originally the top item. Reveal that exact child after the
     * LauSo row is inserted; only use the launcher layout as a version-tolerant fallback.
     */
    private static void ensureNormalBoundaryDivider(
            View nativeRow, ViewGroup shortcuts, Context context) {
        if (!(nativeRow instanceof ViewGroup)) {
            return;
        }
        int dividerId = context.getResources().getIdentifier("divider", "id", LAUNCHER_PACKAGE);
        if (dividerId == 0) {
            return;
        }
        View existing = nativeRow.findViewById(dividerId);
        View template = findNormalDividerTemplate(shortcuts, dividerId, nativeRow);
        if (existing != null) {
            copyNormalDividerLayout(template, existing);
            existing.setVisibility(View.VISIBLE);
            existing.setAlpha(1f);
            alignNormalBoundaryWithRipple(nativeRow, existing);
            return;
        }
        if (template == null || !(template.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        int layoutId = context.getResources().getIdentifier(
                "system_app_shortcut_divide_line", "layout", LAUNCHER_PACKAGE);
        if (layoutId == 0) {
            return;
        }
        FrameLayout.LayoutParams source = (FrameLayout.LayoutParams) template.getLayoutParams();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                source.width, source.height, source.gravity);
        params.setMargins(source.leftMargin, source.topMargin,
                source.rightMargin, source.bottomMargin);
        params.setMarginStart(source.getMarginStart());
        params.setMarginEnd(source.getMarginEnd());

        View divider = LayoutInflater.from(context).inflate(
                layoutId, (ViewGroup) nativeRow, false);
        divider.setId(dividerId);
        divider.setLayoutParams(params);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((ViewGroup) nativeRow).addView(divider, 0);
        alignNormalBoundaryWithRipple(nativeRow, divider);
    }

    /**
     * ColorOS gives the first system row a top padding that later rows do not have. Its
     * divider must still mark the actual boundary shared by the slogan and pressed row.
     */
    private static void alignNormalBoundaryWithRipple(View nativeRow, View divider) {
        int dividerTop = normalDividerTop(nativeRow, divider);
        // Material setup ran before ColorOS restored this special divider. Reapply the
        // bounded row ripple now that the line marks the actual visible top boundary.
        applyShortcutRowRipple(nativeRow, Math.max(0, dividerTop));
    }

    private static int normalDividerTop(View nativeRow, View divider) {
        int dividerTop = divider.getTop();
        if (dividerTop > 0) {
            return dividerTop;
        }
        int topMargin = 0;
        ViewGroup.LayoutParams params = divider.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            topMargin = ((ViewGroup.MarginLayoutParams) params).topMargin;
        }
        return Math.max(nativeRow.getPaddingTop(), topMargin);
    }

    /** Returns the first native row's invisible area above the divider. */
    private static int normalSloganBoundaryReserve(View nativeRow, Context context) {
        int dividerId = context.getResources().getIdentifier("divider", "id", LAUNCHER_PACKAGE);
        View divider = dividerId == 0 ? null : nativeRow.findViewById(dividerId);
        return divider == null ? Math.max(0, nativeRow.getPaddingTop())
                : normalDividerTop(nativeRow, divider);
    }

    /**
     * Assign the divider reserve to the slogan's real bounds and offset the first native row
     * by the same amount. This preserves every on-screen static coordinate while making the
     * built-in, bounded slogan ripple end immediately before the native divider.
     */
    private static boolean offsetFirstNormalNativeRow(View nativeRow, int reserve) {
        ViewGroup.LayoutParams params = nativeRow.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams) || reserve <= 0) {
            return false;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        Integer originalMargin = NORMAL_NATIVE_TOP_MARGINS.get(nativeRow);
        if (originalMargin == null) {
            originalMargin = margins.topMargin;
            NORMAL_NATIVE_TOP_MARGINS.put(nativeRow, originalMargin);
        }
        margins.topMargin = originalMargin - reserve;
        nativeRow.setLayoutParams(margins);
        return true;
    }

    private static void restoreNormalNativeTopMargin(View nativeRow) {
        if (nativeRow == null) return;
        Integer originalMargin = NORMAL_NATIVE_TOP_MARGINS.get(nativeRow);
        if (originalMargin == null) return;
        ViewGroup.LayoutParams params = nativeRow.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        margins.topMargin = originalMargin;
        nativeRow.setLayoutParams(margins);
    }

    private static View findNormalDividerTemplate(View view, int dividerId, View skippedRow) {
        if (view == skippedRow) {
            return null;
        }
        if (view.getId() == dividerId
                && view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            return view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View template = findNormalDividerTemplate(
                    group.getChildAt(index), dividerId, skippedRow);
            if (template != null) {
                return template;
            }
        }
        return null;
    }

    private static void copyNormalDividerLayout(View source, View target) {
        if (source == null || !(source.getLayoutParams() instanceof FrameLayout.LayoutParams)
                || !(target.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams sourceParams = (FrameLayout.LayoutParams) source.getLayoutParams();
        FrameLayout.LayoutParams targetParams = (FrameLayout.LayoutParams) target.getLayoutParams();
        targetParams.width = sourceParams.width;
        targetParams.height = sourceParams.height;
        targetParams.gravity = sourceParams.gravity;
        targetParams.setMargins(sourceParams.leftMargin, sourceParams.topMargin,
                sourceParams.rightMargin, sourceParams.bottomMargin);
        targetParams.setMarginStart(sourceParams.getMarginStart());
        targetParams.setMarginEnd(sourceParams.getMarginEnd());
        target.setLayoutParams(targetParams);
    }

    private static void resizePopupTree(View view, Context context, int width, boolean root) {
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        if (isMenuRowRoot(view, textId)) {
            applyCompactRowGeometry((ViewGroup) view, context, width);
            return;
        }
        if (isFolderIconRowRoot(view, context)) {
            applyCompactFolderIconRow((ViewGroup) view, context, width);
            return;
        }
        if (!root) {
            clearVerticalMargins(view.getLayoutParams());
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                group.setPadding(group.getPaddingLeft(), 0,
                        group.getPaddingRight(), 0);
            }
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width == ViewGroup.LayoutParams.MATCH_PARENT
                || params.width == launcherDimension(context, "deep_shortcut_icon_text_width",
                dp(context, 232)))) {
            params.width = width;
            view.setLayoutParams(params);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                resizePopupTree(group.getChildAt(index), context, width, false);
            }
        }
    }

    /** Normalizes a real Launcher row rather than guessing its Oplus implementation class. */
    private static void applyCompactRowGeometry(ViewGroup row, Context context, int width) {
        int rowHeight = dp(context, IPHONE_SINGLE_ROW_DP);
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        if (rowParams == null) rowParams = new ViewGroup.LayoutParams(width, rowHeight);
        rowParams.width = width;
        rowParams.height = rowHeight;
        clearMargins(rowParams);
        row.setLayoutParams(rowParams);
        row.setMinimumHeight(rowHeight);

        TextView text = findRowText(row, context);
        if (text != null) {
            text.setTranslationY(-dp(context, IPHONE_NATIVE_TEXT_OPTICAL_RAISE_DP));
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, IPHONE_TEXT_SP);
            text.setMinHeight(rowHeight);
            text.setMinimumHeight(rowHeight);
            text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            text.setPaddingRelative(dp(context, IPHONE_TEXT_START_DP),
                    dp(context, IPHONE_NATIVE_VERTICAL_PADDING_DP),
                    dp(context, IPHONE_TEXT_END_DP), dp(context, IPHONE_NATIVE_VERTICAL_PADDING_DP));
            ViewGroup.LayoutParams textParams = text.getLayoutParams();
            if (textParams != null) {
                textParams.width = width;
                if (!ROW_TAG.equals(row.getTag())) {
                    textParams.height = rowHeight;
                }
                if (textParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) textParams;
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
                if (textParams instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) textParams).gravity =
                            Gravity.START | Gravity.CENTER_VERTICAL;
                }
                text.setLayoutParams(textParams);
            }
        }

        View icon = findRowIcon(row, context);
        if (icon != null) {
            ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
            if (iconParams != null) {
                iconParams.width = dp(context, IPHONE_SYSTEM_ICON_DP);
                iconParams.height = dp(context, IPHONE_SYSTEM_ICON_DP);
                if (iconParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins =
                            (ViewGroup.MarginLayoutParams) iconParams;
                    margins.setMarginStart(dp(context, IPHONE_ICON_START_DP));
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
                if (iconParams instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) iconParams).gravity =
                            Gravity.START | Gravity.CENTER_VERTICAL;
                }
                icon.setLayoutParams(iconParams);
            }
        }
    }

    private static boolean isMenuRowRoot(View view, int textId) {
        if (!(view instanceof ViewGroup) || textId == 0) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getId() == textId) return true;
        }
        return false;
    }

    /** Folder menus use one textless horizontal shortcut row for their folder actions. */
    private static boolean isFolderIconRowRoot(View view, Context context) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        int[] iconIds = {
                context.getResources().getIdentifier("folder_icon", "id", LAUNCHER_PACKAGE),
                context.getResources().getIdentifier("folder_icon2", "id", LAUNCHER_PACKAGE),
                context.getResources().getIdentifier("folder_icon3", "id", LAUNCHER_PACKAGE),
        };
        int found = 0;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (!(child instanceof ViewGroup)) continue;
            for (int iconId : iconIds) {
                if (iconId != 0 && hasDirectChildWithId((ViewGroup) child, iconId)) {
                    found++;
                    break;
                }
            }
        }
        return found > 0;
    }

    private static boolean hasDirectChildWithId(ViewGroup group, int id) {
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getId() == id) return true;
        }
        return false;
    }

    private static void applyCompactFolderIconRow(ViewGroup row, Context context, int width) {
        int rowHeight = dp(context, IPHONE_SINGLE_ROW_DP);
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        if (rowParams == null) rowParams = new ViewGroup.LayoutParams(width, rowHeight);
        rowParams.width = width;
        rowParams.height = rowHeight;
        clearMargins(rowParams);
        row.setLayoutParams(rowParams);
        row.setMinimumHeight(rowHeight);

        int[] iconIds = {
                context.getResources().getIdentifier("folder_icon", "id", LAUNCHER_PACKAGE),
                context.getResources().getIdentifier("folder_icon2", "id", LAUNCHER_PACKAGE),
                context.getResources().getIdentifier("folder_icon3", "id", LAUNCHER_PACKAGE),
        };
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            ViewGroup.LayoutParams childParams = child.getLayoutParams();
            if (childParams != null) {
                childParams.height = rowHeight;
                if (childParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins =
                            (ViewGroup.MarginLayoutParams) childParams;
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
                child.setLayoutParams(childParams);
            }
            if (!(child instanceof ViewGroup)) continue;
            for (int iconId : iconIds) {
                if (iconId == 0) continue;
                View icon = child.findViewById(iconId);
                if (icon == null) continue;
                ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
                if (iconParams == null) continue;
                iconParams.width = dp(context, IPHONE_SYSTEM_ICON_DP);
                iconParams.height = dp(context, IPHONE_SYSTEM_ICON_DP);
                if (iconParams instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) iconParams).gravity = Gravity.CENTER;
                    ((FrameLayout.LayoutParams) iconParams).topMargin = 0;
                    ((FrameLayout.LayoutParams) iconParams).bottomMargin = 0;
                }
                icon.setLayoutParams(iconParams);
                break;
            }
        }
    }

    private static TextView findRowText(View row, Context context) {
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        if (!(row instanceof ViewGroup) || textId == 0) return null;
        View child = row.findViewById(textId);
        return child instanceof TextView ? (TextView) child : null;
    }

    private static View findRowIcon(View row, Context context) {
        int iconId = context.getResources().getIdentifier("icon", "id", LAUNCHER_PACKAGE);
        if (!(row instanceof ViewGroup) || iconId == 0) return null;
        return row.findViewById(iconId);
    }

    private static View findFirstNativeRow(View view, Context context) {
        int textId = context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE);
        if (!ROW_TAG.equals(view.getTag()) && isMenuRowRoot(view, textId)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findFirstNativeRow(group.getChildAt(index), context);
            if (result != null) return result;
        }
        return null;
    }

    /** Gives the dynamic slogan row the exact horizontal anchors of this popup's native rows. */
    private static void alignSloganRowWithNativeRow(View sloganRow, View nativeRow, Context context) {
        TextView sloganText = findRowText(sloganRow, context);
        TextView nativeText = findRowText(nativeRow, context);
        if (sloganText != null && nativeText != null) {
            copyHorizontalAnchor(nativeText.getLayoutParams(), sloganText.getLayoutParams());
            sloganText.setLayoutParams(sloganText.getLayoutParams());
        }
        View sloganIcon = findRowIcon(sloganRow, context);
        View nativeIcon = findRowIcon(nativeRow, context);
        if (sloganIcon == null || nativeIcon == null
                || !(sloganIcon.getLayoutParams() instanceof FrameLayout.LayoutParams)
                || !(nativeIcon.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams target = (FrameLayout.LayoutParams) sloganIcon.getLayoutParams();
        FrameLayout.LayoutParams source = (FrameLayout.LayoutParams) nativeIcon.getLayoutParams();
        target.width = source.width;
        target.height = source.height;
        target.setMarginStart(source.getMarginStart());
        target.setMarginEnd(source.getMarginEnd());
        target.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        target.topMargin = 0;
        target.bottomMargin = 0;
        sloganIcon.setLayoutParams(target);
    }

    private static void copyHorizontalAnchor(
            ViewGroup.LayoutParams source, ViewGroup.LayoutParams target) {
        if (!(source instanceof ViewGroup.MarginLayoutParams)
                || !(target instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams sourceMargins = (ViewGroup.MarginLayoutParams) source;
        ViewGroup.MarginLayoutParams targetMargins = (ViewGroup.MarginLayoutParams) target;
        targetMargins.setMarginStart(sourceMargins.getMarginStart());
        targetMargins.setMarginEnd(sourceMargins.getMarginEnd());
        targetMargins.leftMargin = sourceMargins.leftMargin;
        targetMargins.rightMargin = sourceMargins.rightMargin;
    }

    private static void clearVerticalMargins(ViewGroup.LayoutParams params) {
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.topMargin = 0;
            margins.bottomMargin = 0;
        }
    }

    private static void clearMargins(ViewGroup.LayoutParams params) {
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMargins(0, 0, 0, 0);
        }
    }

    private static void setWidth(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) params = new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.width = width;
        view.setLayoutParams(params);
    }

    private static void hideCompactDividers(Context context, ViewGroup shortcuts) {
        int dividerId = context.getResources().getIdentifier("divider", "id", LAUNCHER_PACKAGE);
        hideCompactDividers(shortcuts, dividerId, context);
    }

    private static void hideCompactDividers(View view, int dividerId, Context context) {
        if (dividerId != 0 && view.getId() == dividerId) {
            COMPACT_HIDDEN_VISIBILITIES.putIfAbsent(view, view.getVisibility());
            view.setVisibility(View.GONE);
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            hideCompactDividers(child, dividerId, context);
            // ColorOS inserts a dedicated, textless divider container between shortcut
            // groups. It has no semantic content and creates the visible gap in compact mode.
            if (isCompactSpacer(child, context)) {
                COMPACT_HIDDEN_VISIBILITIES.putIfAbsent(child, child.getVisibility());
                child.setVisibility(View.GONE);
            }
        }
    }

    private static void restoreCompactHiddenViews(View view) {
        Integer original = COMPACT_HIDDEN_VISIBILITIES.get(view);
        if (original != null) view.setVisibility(original);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            restoreCompactHiddenViews(group.getChildAt(index));
        }
    }

    private static boolean isCompactSpacer(View view, Context context) {
        if (!(view instanceof ViewGroup) || view.findViewById(
                context.getResources().getIdentifier("bubble_text", "id", LAUNCHER_PACKAGE)) != null) {
            return false;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int height = params == null ? view.getHeight() : params.height;
        return height > 0 && height <= dp(context, 12);
    }

    private void installLauncherVerificationReceiver(ClassLoader classLoader) {
        if (verificationReceiverInstalled.get()
                || !verificationReceiverRegistrationScheduled.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int attemptsRemaining = 20;

            @Override
            public void run() {
                if (verificationReceiverInstalled.get()) {
                    verificationReceiverRegistrationScheduled.set(false);
                    return;
                }
                if (tryInstallLauncherVerificationReceiver(classLoader)) {
                    verificationReceiverRegistrationScheduled.set(false);
                    return;
                }
                attemptsRemaining--;
                if (attemptsRemaining > 0) {
                    handler.postDelayed(this, 250L);
                } else {
                    verificationReceiverRegistrationScheduled.set(false);
                    log(Log.WARN, TAG, "Launcher verification receiver was unavailable after startup");
                }
            }
        });
    }

    /**
     * LSPosed invokes onPackageReady before Launcher has necessarily attached its Application.
     * Registration must therefore retry briefly on Launcher main thread instead of treating that
     * first null currentApplication result as a permanent verification failure.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API 26-32 has no receiver flag overload.
    private boolean tryInstallLauncherVerificationReceiver(ClassLoader classLoader) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, classLoader);
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            if (!(application instanceof Context)) return false;
            Context context = ((Context) application).getApplicationContext();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ModuleStatusProvider.ACTION_VERIFY_LAUNCHER.equals(
                            intent.getAction())) {
                        return;
                    }
                    ModuleStatusProvider.confirmLauncherVerification(receiverContext,
                            intent.getStringExtra(ModuleStatusProvider.EXTRA_REQUEST_ID));
                }
            };
            IntentFilter filter = new IntentFilter(ModuleStatusProvider.ACTION_VERIFY_LAUNCHER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter,
                        ModuleStatusProvider.PERMISSION_VERIFY_LAUNCHER, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter,
                        ModuleStatusProvider.PERMISSION_VERIFY_LAUNCHER, null);
            }
            verificationReceiverInstalled.set(true);
            log(Log.INFO, TAG, "Launcher verification receiver installed");
            return true;
        } catch (Throwable error) {
            log(Log.DEBUG, TAG, "Launcher verification receiver unavailable", error);
            return false;
        }
    }

    private void closePopup(Object popup) {
        try {
            // ColorOS exposes animateClose() as a lightweight animation entry. On some
            // releases it does not run the full popup lifecycle that clears the source
            // icon's long-press/drag state. Prefer the public no-arg handleClose() path,
            // which delegates to handleClose(true) and performs that cleanup, while
            // retaining compatibility with builds that expose only the boolean overload.
            Method noArgClose = findMethod(popup.getClass(), "handleClose");
            if (noArgClose != null) {
                try {
                    noArgClose.invoke(popup);
                    return;
                } catch (Throwable error) {
                    log(Log.DEBUG, TAG, "No-arg popup close failed; trying boolean close", error);
                }
            }
            Method fullClose = findMethod(popup.getClass(), "handleClose", boolean.class);
            if (fullClose != null) {
                try {
                    fullClose.invoke(popup, true);
                    return;
                } catch (Throwable error) {
                    log(Log.DEBUG, TAG, "Boolean popup close failed; trying animateClose", error);
                }
            }
            Method animateClose = findMethod(popup.getClass(), "animateClose");
            if (animateClose != null) {
                try {
                    animateClose.invoke(popup);
                    return;
                } catch (Throwable error) {
                    log(Log.DEBUG, TAG, "Animated popup close failed", error);
                }
            }
            throw new NoSuchMethodException("No popup close method");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not close popup after slogan click", error);
        }
    }

    /** Injects a down/up Back pair with no artificial delay so the native popup owns cleanup. */
    private static boolean injectFastBackKey() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = findMethod(inputManagerClass, "getInstance");
            Object inputManager = getInstance == null ? null : getInstance.invoke(null);
            Method inject = findMethod(inputManagerClass, "injectInputEvent",
                    InputEvent.class, int.class);
            if (inputManager == null || inject == null) {
                return false;
            }
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BACK, 0, 0, -1, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
            Object downResult = inject.invoke(inputManager, down, 0);
            Object upResult = inject.invoke(inputManager, up, 0);
            return Boolean.TRUE.equals(downResult) && Boolean.TRUE.equals(upResult);
        } catch (Throwable error) {
            Log.w(TAG, "Fast Back injection unavailable; using popup close fallback", error);
            return false;
        }
    }

    private static boolean isPopupStillVisible(Object popup) {
        if (!(popup instanceof View)) {
            return false;
        }
        View view = (View) popup;
        return view.getVisibility() == View.VISIBLE && view.isShown();
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static boolean readBooleanField(Object target, String name, boolean fallback) {
        Object value = readFieldUnchecked(target, name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object readFieldUnchecked(Object target, String name) {
        try {
            return readField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeField(Object target, String name, Object value) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                Log.d(TAG, "Unable to update popup field " + name, error);
                return;
            }
        }
    }

    private static String targetPackage(Object itemInfo) {
        if (itemInfo == null) {
            return null;
        }
        try {
            Method getTargetPackage = itemInfo.getClass().getMethod("getTargetPackage");
            Object packageName = getTargetPackage.invoke(itemInfo);
            return packageName instanceof String && !((String) packageName).isEmpty()
                    ? (String) packageName
                    : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private static InsetDrawable smallCircle(Context context, int color, int insetDp) {
        return new InsetDrawable(circle(color), dp(context, insetDp));
    }

    private static void setDividerVisible(View row, boolean visible) {
        try {
            Method setDividerVisible = row.getClass().getMethod("setDividerVisible", boolean.class);
            setDividerVisible.invoke(row, visible);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Launcher menu row has no divider visibility hook", error);
        }
    }

    private static void configureMenuText(TextView text, SloganRule rule, boolean compact) {
        if (!compact) {
            text.setText(menuText(rule.getTitle(), rule.getSubtitle()));
            return;
        }

        // A line is limited only by the actual pixel width left inside the compact row.
        // Each code point contributes its measured advance, so narrow digits/Latin text
        // can use more code points while wide glyphs consume the same physical budget.
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, IPHONE_TEXT_SP);
        float availableWidth = dp(text.getContext(), IPHONE_MENU_WIDTH_DP
                - IPHONE_TEXT_START_DP - IPHONE_TEXT_END_DP);
        String title = wrapByVisualWidth(rule.getTitle(), text.getPaint(), availableWidth);
        String subtitle = wrapByVisualWidth(rule.getSubtitle(), subtitlePaint(text), availableWidth);
        text.setText(menuText(title, subtitle));
    }

    private static android.text.TextPaint subtitlePaint(TextView text) {
        android.text.TextPaint paint = new android.text.TextPaint(text.getPaint());
        paint.setTextSize(text.getTextSize() * 0.76f);
        return paint;
    }

    private static String wrapByVisualWidth(
            String value, android.text.TextPaint paint, float maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0f) return value == null ? "" : value;
        StringBuilder result = new StringBuilder(value.length() + 8);
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int count = Character.charCount(codePoint);
            String glyph = value.substring(index, index + count);
            index += count;
            if (Character.isWhitespace(codePoint) && line.length() == 0) continue;

            float candidateWidth = paint.measureText(line.toString() + glyph);
            if (candidateWidth > maxWidth && line.length() > 0) {
                if (isClosingPunctuation(codePoint)) {
                    // Keep closing punctuation attached to the preceding phrase even if
                    // its small advance crosses the visual threshold by a few pixels.
                    line.append(glyph);
                    appendWrappedLine(result, line);
                    line.setLength(0);
                } else {
                    appendWrappedLine(result, line);
                    line.setLength(0);
                    if (!Character.isWhitespace(codePoint)) line.append(glyph);
                }
            } else {
                line.append(glyph);
            }
        }
        appendWrappedLine(result, line);
        return result.toString();
    }

    private static void appendWrappedLine(StringBuilder result, StringBuilder line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        if (end == 0) return;
        if (result.length() > 0) result.append('\n');
        result.append(line, 0, end);
    }

    private static boolean isClosingPunctuation(int codePoint) {
        return "，。！？；：、）】》」』〉〕〗”’％%!?;:,.\"'\u00bb\u2019\u201d".indexOf(codePoint) >= 0;
    }

    private static CharSequence menuText(CharSequence title, String subtitle) {
        SpannableStringBuilder value = new SpannableStringBuilder(title);
        if (!subtitle.isEmpty()) {
            int start = value.length() + 1;
            value.append('\n').append(subtitle);
            value.setSpan(new RelativeSizeSpan(0.76f), start, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setSpan(new ForegroundColorSpan(Color.rgb(103, 114, 130)), start, value.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return value;
    }

    private static String contentDescription(SloganRule rule) {
        return rule.getSubtitle().isEmpty()
                ? rule.getTitle()
                : rule.getTitle() + "，" + rule.getSubtitle();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
