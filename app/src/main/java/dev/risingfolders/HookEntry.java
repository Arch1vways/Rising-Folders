package dev.risingfolders;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * Headless LSPosed module (libxposed API 102, scope {@code com.bbk.launcher2}) that removes the
 * horizontal and vertical resize limits on OriginOS desktop folders and scales the inner icon
 * grid so icons expand cleanly across enlarged folders.
 */
public final class HookEntry extends XposedModule {
    private static final String HOOK_TAG = "FolderResize";
    private static final String TARGET_LAUNCHER = "com.bbk.launcher2";

    private static final String CLS_MORPH_RESIZE_VIEW = "com.bbk.launcher2.exploredesktop.ui.icon.MorphResizeView";
    private static final String CLS_MORPH_HELPER = "o2.a";
    private static final String CLS_MORPH_CANDIDATE = "o2.a$a";
    private static final String CLS_MORPH_CONTEXT = "i8.a";
    private static final String CLS_REGION_UTIL = "xc.l";
    private static final String CLS_CELL_AND_SPAN = "m7.j";
    private static final String CLS_EX_CELL_AND_SPAN = "v8.u";
    private static final String CLS_DEFORM_STYLE_MGR = "g8.b";
    private static final String CLS_FOLDER_PARAMS = "s8.a";
    private static final String CLS_ICON_MASK_UTILS = "g8.c";
    private static final String CLS_FOLDER_ICON_UTILITIES = "v8.f";
    private static final String CLS_ICON_PARAM_UTILS = "v8.o";
    private static final String CLS_OPEN_CLOSE_ANIM_HELPER = "pc.x";
    private static final String CLS_PAGED_VIEW = "com.bbk.launcher2.ui.PagedView";
    private static final String CLS_FOLDER_PREVIEW = "com.bbk.launcher2.ui.originfolder.OriginFolderPreview";
    private static final String CLS_ITEM_ICON_VIEW = "com.bbk.launcher2.launcherIcon.ui.icon.ItemIcon";
    private static final String CLS_FOLDER_CONTAINER_PRESENTER = "pc.c";
    private static final String CLS_FOLDER_CONTAINER = "com.bbk.launcher2.ui.originfolder.ContainerForIconInFolder";
    private static final String CLS_ICON_UTILITIES = "v8.p";
    private static final String CLS_ITEM_INFO_BASE = "com.bbk.launcher2.launcherCommon.mode.f";
    private static final String CLS_ITEM_INFO = "com.bbk.launcher2.launcherCommon.mode.e";
    private static final String CLS_FOLDER_CLICK_PRESENTER = "rc.y0";
    private static final String CLS_CELL_LAYOUT = "com.bbk.launcher2.ui.CellLayout";
    private static final String CLS_FOLDER_ICON = "com.bbk.launcher2.ui.originfolder.OriginFolderIcon";

    private static final int CUSTOM_STATE_TAG = 0x10000;
    private static final int CUSTOM_STATE_MASK = 0xFFFF0000;
    private static final int MAX_FOLDER_ITEMS = 64;

    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    static volatile XposedInterface xposed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_LAUNCHER.equals(param.getPackageName())) return;
        ClassLoader cl = classLoader(param);
        hookMorphResizeView(cl);
        hookMorphResizeHelper(cl);
        hookCellAndSpanEncoding(cl);
        hookDeformStyleAndFolderGrid(cl);
        hookFolderMorphAnimationBounds(cl);
        hookFolderOpenCloseAnimation(cl);
        hookFolderCapacityCaps(cl);
    }

    private static ClassLoader classLoader(PackageReadyParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            if (cl != null) return cl;
        } catch (Throwable ignored) {
        }
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable ignored) {
            return HookEntry.class.getClassLoader();
        }
    }

    /**
     * Scales inner folder grid columns/rows proportionally to the desktop cell span:
     * {@code 1 -> 1}, {@code 2 -> 3} (stock), {@code 3 -> 4}, {@code 4 -> 6}, {@code 5 -> 7}, etc.
     */
    private static int gridAxisCount(int span) {
        if (span <= 1) return 1;
        if (span == 2) return 3;
        return Math.max(3, (span * 3) / 2);
    }

    /**
     * Expands {@code MorphResizeView.d(CellLayout)} drag bounds from the hardcoded 3x3 box around
     * the folder to the full grid bounds ({@code this.f}) for {@code OriginFolderIcon}.
     */
    private static void hookMorphResizeView(ClassLoader cl) {
        try {
            Class<?> viewCls = Class.forName(CLS_MORPH_RESIZE_VIEW, false, cl);
            if (!markInstalled(viewCls)) return;
            Field folderField = viewCls.getField("n");
            Field fullGridRectField = viewCls.getField("f");
            interceptMethods(viewCls, "d", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self != null && folderField.get(self) != null) {
                    Object fullRect = fullGridRectField.get(self);
                    if (fullRect instanceof Rect) {
                        return new Rect((Rect) fullRect);
                    }
                }
                return res;
            });
        } catch (Throwable t) {
            hookError("hookMorphResizeView", t);
        }
    }

    /**
     * Replaces {@code o2.a.g(CellLayout, OriginFolderIcon)} candidate generation so folders can
     * resize across any valid horizontal/vertical span on the grid instead of capping at 2x2.
     */
    private static void hookMorphResizeHelper(ClassLoader cl) {
        try {
            Class<?> helperCls = Class.forName(CLS_MORPH_HELPER, false, cl);
            if (!markInstalled(helperCls)) return;

            Class<?> candCls = Class.forName(CLS_MORPH_CANDIDATE, false, cl);
            Class<?> ctxCls = Class.forName(CLS_MORPH_CONTEXT, false, cl);
            Class<?> regionCls = Class.forName(CLS_REGION_UTIL, false, cl);
            Class<?> cellSpanBaseCls = Class.forName(CLS_CELL_AND_SPAN, false, cl);
            Class<?> cellSpanCls = Class.forName(CLS_EX_CELL_AND_SPAN, false, cl);
            Class<?> cellLayoutCls = Class.forName(CLS_CELL_LAYOUT, false, cl);
            Class<?> folderIconCls = Class.forName(CLS_FOLDER_ICON, false, cl);

            Field listField = helperCls.getField("a");
            Field baseRectField = helperCls.getField("b");
            Field fA = cellSpanBaseCls.getField("a");
            Field fB = cellSpanBaseCls.getField("b");
            Method getCtx = ctxCls.getMethod("c");
            Method getCellSpan = ctxCls.getMethod("f");
            Method getFolderSpan = folderIconCls.getMethod("getCellAndSpan");
            Method getCountX = cellLayoutCls.getMethod("getCellCountX");
            Method getCountY = cellLayoutCls.getMethod("getCellCountY");
            Method calcFolderRect = regionCls.getMethod("e", cellLayoutCls, cellSpanCls, folderIconCls, Rect.class);
            Method helperDone = helperCls.getDeclaredMethod("d");
            helperDone.setAccessible(true);
            Constructor<?> spanCtor = cellSpanCls.getConstructor(int.class, int.class, int.class, int.class);
            Constructor<?> candCtor = candCls.getConstructor(cellSpanCls, Rect.class);

            interceptMethods(helperCls, "g", chain -> {
                Object self = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (self == null || args.size() < 2) return chain.proceed();
                Object cellLayout = args.get(0);
                Object folderIcon = args.get(1);
                if (cellLayout == null || folderIcon == null) return chain.proceed();

                try {
                    Object morphCtx = getCtx.invoke(null);
                    Object currentSpan = morphCtx != null ? getCellSpan.invoke(morphCtx) : null;
                    if (currentSpan == null) currentSpan = getFolderSpan.invoke(folderIcon);
                    if (currentSpan == null) return chain.proceed();

                    int countX = (Integer) getCountX.invoke(cellLayout);
                    int countY = (Integer) getCountY.invoke(cellLayout);
                    if (countX <= 0 || countY <= 0) return chain.proceed();

                    int anchorX = fA.getInt(currentSpan);
                    int anchorY = fB.getInt(currentSpan);

                    Rect baseRect = (Rect) baseRectField.get(self);
                    if (baseRect == null) return chain.proceed();
                    calcFolderRect.invoke(null, cellLayout, currentSpan, folderIcon, baseRect);
                    Rect fixedBase = new Rect(baseRect);

                    @SuppressWarnings("unchecked")
                    List<Object> candidates = (List<Object>) listField.get(self);
                    if (candidates == null) return chain.proceed();
                    candidates.clear();

                    int minX = Math.max(0, Math.min(anchorX, countX - 1));
                    int minY = Math.max(0, Math.min(anchorY, countY - 1));
                    for (int x = 0; x <= minX; x++) {
                        int minSpanX = Math.max(1, minX - x + 1);
                        int maxSpanX = countX - x;
                        for (int y = 0; y <= minY; y++) {
                            int minSpanY = Math.max(1, minY - y + 1);
                            int maxSpanY = countY - y;
                            for (int spanX = minSpanX; spanX <= maxSpanX; spanX++) {
                                for (int spanY = minSpanY; spanY <= maxSpanY; spanY++) {
                                    Object candSpan = spanCtor.newInstance(x, y, spanX, spanY);
                                    Rect candRect = new Rect();
                                    calcFolderRect.invoke(null, cellLayout, candSpan, folderIcon, candRect);
                                    if (!Rect.intersects(fixedBase, candRect)) continue;
                                    candidates.add(candCtor.newInstance(candSpan, candRect));
                                }
                            }
                        }
                    }

                    @SuppressWarnings({"rawtypes", "unchecked"})
                    List<Comparable> comparable = (List) candidates;
                    Collections.sort(comparable);
                    helperDone.invoke(self);
                    return null;
                } catch (Throwable inner) {
                    hookError("hookMorphResizeHelper.g", inner);
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            hookError("hookMorphResizeHelper", t);
        }
    }

    /**
     * Prevents {@code spanX + 2 * spanY} collisions (e.g. {@code 3x1 == 1x2}, {@code 4x1 == 2x2})
     * and preserves exact {@code spanX}/{@code spanY} when {@code spanX > 2 || spanY > 2}.
     */
    private static void hookCellAndSpanEncoding(ClassLoader cl) {
        try {
            Class<?> baseCls = Class.forName(CLS_CELL_AND_SPAN, false, cl);
            if (markInstalled(baseCls)) {
                Field fC = baseCls.getField("c");
                Field fD = baseCls.getField("d");
                interceptMethods(baseCls, "b", chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        int spanX = fC.getInt(self);
                        int spanY = fD.getInt(self);
                        if (spanX > 2 || spanY > 2) {
                            return CUSTOM_STATE_TAG | ((spanX & 0xFF) << 8) | (spanY & 0xFF);
                        }
                    }
                    return chain.proceed();
                });
            }

            Class<?> exCls = Class.forName(CLS_EX_CELL_AND_SPAN, false, cl);
            if (markInstalled(exCls)) {
                interceptMethods(exCls, "e", chain -> {
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof Integer) {
                        int state = (Integer) arg0;
                        if ((state & CUSTOM_STATE_MASK) == CUSTOM_STATE_TAG) {
                            return (state >> 8) & 0xFF;
                        }
                    }
                    return chain.proceed();
                });
                interceptMethods(exCls, "f", chain -> {
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof Integer) {
                        int state = (Integer) arg0;
                        if ((state & CUSTOM_STATE_MASK) == CUSTOM_STATE_TAG) {
                            return state & 0xFF;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            hookError("hookCellAndSpanEncoding", t);
        }
    }

    /**
     * Gives each custom {@code spanX x spanY} layout its own cache entry in
     * {@code DeformIconSizeStyleManager} and rebuilds {@code s8.a} ({@code OriginFolderParams})
     * with scaled column/row counts so icons spread across the entire enlarged folder.
     */
    private static void hookDeformStyleAndFolderGrid(ClassLoader cl) {
        try {
            Class<?> mgrCls = Class.forName(CLS_DEFORM_STYLE_MGR, false, cl);
            if (!markInstalled(mgrCls)) return;

            interceptMethods(mgrCls, "W", chain -> {
                Object res = chain.proceed();
                List<Object> args = chain.getArgs();
                if (res instanceof String && args.size() >= 4 && args.get(3) instanceof Integer) {
                    int state = (Integer) args.get(3);
                    if ((state & CUSTOM_STATE_MASK) == CUSTOM_STATE_TAG) {
                        int spanX = (state >> 8) & 0xFF;
                        int spanY = state & 0xFF;
                        return res + "_custom_" + spanX + "x" + spanY;
                    }
                }
                return res;
            });

            Class<?> paramsCls = Class.forName(CLS_FOLDER_PARAMS, false, cl);
            Field fC = paramsCls.getField("c");
            Field fD = paramsCls.getField("d");
            Field fL = paramsCls.getField("l");
            Field fM = paramsCls.getField("m");
            Field fP = paramsCls.getField("p");
            Field fQ = paramsCls.getField("q");
            Field fR = paramsCls.getField("r");
            Field fS = paramsCls.getField("s");
            Field fT = paramsCls.getField("t");
            Field fU = paramsCls.getField("u");
            Field fX = paramsCls.getField("x");
            Field fY = paramsCls.getField("y");
            Field fBoolD = paramsCls.getField("D");
            Field fE = paramsCls.getField("E");
            Constructor<?> paramsCtor = paramsCls.getConstructor(
                    int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, boolean.class, float.class, int.class);
            Method calcLayout = paramsCls.getMethod("c");
            Method calcPreview = paramsCls.getMethod("e");
            Method applyOrientation = paramsCls.getMethod("n", int.class);

            Field fPadL = mgrCls.getField("l");
            Field fPadT = mgrCls.getField("n");

            interceptMethods(mgrCls, "a0", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (res == null || self == null || args.isEmpty() || !(args.get(0) instanceof Integer)) {
                    return res;
                }
                int state = (Integer) args.get(0);
                if ((state & CUSTOM_STATE_MASK) != CUSTOM_STATE_TAG) return res;

                int spanX = (state >> 8) & 0xFF;
                int spanY = state & 0xFF;
                int cols = gridAxisCount(spanX);
                int rows = gridAxisCount(spanY);

                int width = fC.getInt(res);
                int height = fD.getInt(res);
                if (width <= 0 || height <= 0) return res;

                int padLeft = fPadL.getInt(self);
                int padTop = fPadT.getInt(self);
                int smallPadX = fX.getInt(res);
                int smallPadY = fY.getInt(res);
                boolean dFlag = fBoolD.getBoolean(res);
                float iconInCell = fE.getFloat(res);

                Object expanded;
                if (width <= height) {
                    expanded = paramsCtor.newInstance(
                            width, height, cols, rows, padLeft, padTop,
                            smallPadX, smallPadY, dFlag, iconInCell, 0);
                    calcLayout.invoke(expanded);
                    applyOrientation.invoke(expanded, 0);
                } else {
                    expanded = paramsCtor.newInstance(
                            height, width, rows, cols, padLeft, padTop,
                            smallPadX, smallPadY, dFlag, iconInCell, 0);
                    calcLayout.invoke(expanded);
                    applyOrientation.invoke(expanded, 1);
                }

                // Stock s8.a.d() hardcodes division by 2 instead of (axisCount - 1) when fitting
                // the secondary gap, pushing rows/columns beyond the folder's bottom/right bounds
                // whenever rows or cols >= 4 (e.g., 4x3). Recompute symmetric padding and gaps.
                int availW = Math.max(1, width - 2 * padLeft);
                int availH = Math.max(1, height - 2 * padTop);
                int iconSize = Math.max(1, Math.min(availW / cols, availH / rows));

                int gapX = 0;
                int padX;
                if (cols > 1) {
                    int remX = Math.max(0, availW - cols * iconSize);
                    gapX = remX / (cols - 1);
                    padX = Math.max(padLeft, (width - cols * iconSize - (cols - 1) * gapX) / 2);
                } else {
                    padX = Math.max(0, (width - iconSize) / 2);
                }

                int gapY = 0;
                int padY;
                if (rows > 1) {
                    int remY = Math.max(0, availH - rows * iconSize);
                    gapY = remY / (rows - 1);
                    padY = Math.max(padTop, (height - rows * iconSize - (rows - 1) * gapY) / 2);
                } else {
                    padY = Math.max(0, (height - iconSize) / 2);
                }

                fR.setInt(expanded, iconSize);
                fS.setInt(expanded, iconSize);
                calcPreview.invoke(expanded);
                fT.setInt(expanded, iconSize);
                fU.setInt(expanded, iconSize);
                fL.setInt(expanded, padX);
                fM.setInt(expanded, padY);
                fP.setInt(expanded, gapX);
                fQ.setInt(expanded, gapY);
                return expanded;
            });
        } catch (Throwable t) {
            hookError("hookDeformStyleAndFolderGrid", t);
        }
    }

    private static final ThreadLocal<Object> PENDING_MORPH_UNION_SPAN = new ThreadLocal<>();

    /**
     * Expands the temporary layout bounding box used during {@code OFoldMAH-doMorphAnim}
     * ({@code rc.z0$d.b()} and {@code rc.z0$a.b()}) from hardcoded {@code 2x2} to the true
     * union of source and target spans so enlarged folders animate smoothly without clipping.
     */
    private static void hookFolderMorphAnimationBounds(ClassLoader cl) {
        try {
            Class<?> cellSpanBaseCls = Class.forName(CLS_CELL_AND_SPAN, false, cl);
            Class<?> presenterCls = Class.forName("rc.z0", false, cl);
            Class<?> baseMorphPresCls = Class.forName("t8.l", false, cl);
            Class<?> folderIconCls = Class.forName(CLS_FOLDER_ICON, false, cl);
            Class<?> itemBaseCls = Class.forName("e8.h", false, cl);

            Field fA = cellSpanBaseCls.getField("a");
            Field fB = cellSpanBaseCls.getField("b");
            Field fC = cellSpanBaseCls.getField("c");
            Field fD = cellSpanBaseCls.getField("d");
            Field presSrcField = baseMorphPresCls.getField("F");
            Constructor<?> cellSpanBaseCtor = cellSpanBaseCls.getConstructor(int.class, int.class, int.class, int.class);

            for (String listenerName : new String[]{"rc.z0$d", "rc.z0$a"}) {
                Class<?> listenerCls = Class.forName(listenerName, false, cl);
                if (!markInstalled(listenerCls)) continue;
                Field tgtField = listenerCls.getField("a");
                Field presField = listenerCls.getField("d");
                interceptMethods(listenerCls, "b", chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        Object tgt = tgtField.get(self);
                        Object presenter = presField.get(self);
                        Object src = presenter != null && presenterCls.isInstance(presenter)
                                ? presSrcField.get(presenter) : null;
                        if (tgt != null && src != null) {
                            int minX = Math.min(fA.getInt(tgt), fA.getInt(src));
                            int minY = Math.min(fB.getInt(tgt), fB.getInt(src));
                            int maxR = Math.max(fA.getInt(tgt) + fC.getInt(tgt), fA.getInt(src) + fC.getInt(src));
                            int maxB = Math.max(fB.getInt(tgt) + fD.getInt(tgt), fB.getInt(src) + fD.getInt(src));
                            int unionW = Math.max(1, maxR - minX);
                            int unionH = Math.max(1, maxB - minY);
                            if (unionW > 2 || unionH > 2) {
                                PENDING_MORPH_UNION_SPAN.set(cellSpanBaseCtor.newInstance(minX, minY, unionW, unionH));
                                try {
                                    return chain.proceed();
                                } finally {
                                    PENDING_MORPH_UNION_SPAN.remove();
                                }
                            }
                        }
                    }
                    return chain.proceed();
                });
            }

            interceptMethods(itemBaseCls, "z4", chain -> proceedWithPendingUnionSpan(chain, 1, cellSpanBaseCls));
            interceptMethods(folderIconCls, "X6", chain -> proceedWithPendingUnionSpan(chain, 1, cellSpanBaseCls));
        } catch (Throwable t) {
            hookError("hookFolderMorphAnimationBounds", t);
        }
    }

    private static Object proceedWithPendingUnionSpan(
            XposedInterface.Chain chain, int spanArgIndex, Class<?> spanCls) throws Throwable {
        Object unionSpan = PENDING_MORPH_UNION_SPAN.get();
        if (unionSpan != null) {
            List<Object> args = chain.getArgs();
            if (args.size() > spanArgIndex && spanCls.isInstance(args.get(spanArgIndex))) {
                Object[] updated = args.toArray();
                updated[spanArgIndex] = unionSpan;
                return chain.proceed(updated);
            }
        }
        return chain.proceed();
    }

    /**
     * Raises hardcoded 9/12-icon folder preview limits so extra grid cells populate with app icons.
     */
    private static void hookFolderCapacityCaps(ClassLoader cl) {
        try {
            Class<?> paramsCls = Class.forName(CLS_FOLDER_PARAMS, false, cl);
            Field hField = paramsCls.getDeclaredField("H");
            hField.setAccessible(true);
            hField.setInt(null, MAX_FOLDER_ITEMS);

            if (markInstalled(paramsCls)) {
                Method getMaxVisible = paramsCls.getMethod("k");
                interceptMethods(paramsCls, "i", chain -> {
                    List<Object> args = chain.getArgs();
                    Object self = chain.getThisObject();
                    if (self != null && !args.isEmpty() && args.get(0) instanceof Integer) {
                        int index = (Integer) args.get(0);
                        int maxVisible = (Integer) getMaxVisible.invoke(self);
                        if (index >= maxVisible) {
                            return -1;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            hookError("hookFolderParamsCap", t);
        }

        try {
            Class<?> presenterCls = Class.forName(CLS_FOLDER_CONTAINER_PRESENTER, false, cl);
            Field fField = presenterCls.getDeclaredField("f");
            fField.setAccessible(true);
            fField.setInt(null, MAX_FOLDER_ITEMS);
        } catch (Throwable t) {
            hookError("hookPresenterCap", t);
        }

        try {
            Class<?> containerCls = Class.forName(CLS_FOLDER_CONTAINER, false, cl);
            if (markInstalled(containerCls)) {
                Class<?> itemBaseCls = Class.forName(CLS_ITEM_INFO_BASE, false, cl);
                Class<?> itemCls = Class.forName(CLS_ITEM_INFO, false, cl);
                Class<?> clickPresCls = Class.forName(CLS_FOLDER_CLICK_PRESENTER, false, cl);

                Field oField = containerCls.getField("o");
                Method addSingle = containerCls.getMethod("f", itemCls, boolean.class, boolean.class);
                Method getItemPresenter = itemBaseCls.getMethod("T4");
                Method setFolderItemFlag = clickPresCls.getMethod("S5", int.class);

                Method lsaSize = null;
                Method lsaGet = null;
                for (Method m : containerCls.getDeclaredMethods()) {
                    if ("c".equals(m.getName()) && m.getParameterCount() == 3) {
                        Class<?> lsaCls = m.getParameterTypes()[0];
                        lsaSize = lsaCls.getMethod("size");
                        lsaGet = lsaCls.getMethod("get", long.class);
                        break;
                    }
                }
                final Method sizeM = lsaSize;
                final Method getM = lsaGet;
                if (sizeM != null && getM != null) {
                    interceptMethods(containerCls, "c", chain -> {
                        Object self = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        boolean limit9 = args.size() >= 2 && Boolean.TRUE.equals(args.get(1));
                        boolean zArg = args.size() >= 3 && Boolean.TRUE.equals(args.get(2));
                        Object sparseArray = !args.isEmpty() ? args.get(0) : null;
                        boolean oFlag = self != null && limit9 && oField.getBoolean(self);

                        Object res = chain.proceed();

                        if (self != null && limit9 && sparseArray != null) {
                            int size = (Integer) sizeM.invoke(sparseArray);
                            int limit = Math.min(size, MAX_FOLDER_ITEMS);
                            for (int i = 9; i < limit; i++) {
                                Object item = getM.invoke(sparseArray, (long) i);
                                if (oFlag && item != null) {
                                    Object pres = getItemPresenter.invoke(item);
                                    if (pres != null && clickPresCls.isInstance(pres)) {
                                        setFolderItemFlag.invoke(pres, 0);
                                    }
                                }
                                addSingle.invoke(self, item, true, zArg);
                            }
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            hookError("hookContainerCap", t);
        }

        try {
            Class<?> iconUtilsCls = Class.forName(CLS_ICON_UTILITIES, false, cl);
            if (markInstalled(iconUtilsCls)) {
                interceptMethods(iconUtilsCls, "r", chain -> {
                    List<Object> args = chain.getArgs();
                    if (args.size() >= 2 && args.get(0) instanceof Integer && args.get(1) instanceof Integer) {
                        int spanX = (Integer) args.get(0);
                        int spanY = (Integer) args.get(1);
                        if (spanX > 2 || spanY > 2) {
                            return gridAxisCount(spanX) * gridAxisCount(spanY);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            hookError("hookIconUtilitiesCap", t);
        }
    }

    /**
     * Fixes icon target coordinates and rendering during open/close animations for custom-span folders:
     * <ul>
     *   <li>{@code v8.f.W()} only maps morph states {@code 4..6} and falls back to {@code 1x1} for
     *       custom spans, causing negative horizontal padding and shifting closing icons left of the folder.</li>
     *   <li>Hides unused preview placeholder {@code l0} when page 0 has no preview slot ({@code F > 12}).</li>
     *   <li>Disables clipping on {@code OriginFolderMain -> OriginFolderPagedView -> OriginFolderCellLayout
     *       -> ShortcutAndWidgetContainer} (including {@code PagedView.dispatchDraw}'s {@code clipRect})
     *       so columns beyond column 2 (e.g., rightmost column of {@code 3x3}) are not sliced during animation.</li>
     *   <li>Smoothly fades in closed-folder icons not present on open page 0 ({@code rank >= 12} and
     *       preview when {@code F > 12}) during close progress so the bottom rows appear without a ~0.5s pop-in.</li>
     * </ul>
     */
    private static void hookFolderOpenCloseAnimation(ClassLoader cl) {
        try {
            Class<?> folderIconUtils = Class.forName(CLS_FOLDER_ICON_UTILITIES, false, cl);
            if (markInstalled(folderIconUtils)) {
                Class<?> maskUtils = Class.forName(CLS_ICON_MASK_UTILS, false, cl);
                Class<?> iconParamUtils = Class.forName(CLS_ICON_PARAM_UTILS, false, cl);
                Method getLauncherMaskUtils = maskUtils.getMethod("L0");
                Method calcMaskData = null;
                for (Method m : maskUtils.getDeclaredMethods()) {
                    if ("z".equals(m.getName()) && m.getParameterCount() == 7) {
                        calcMaskData = m;
                        break;
                    }
                }
                Method maskToBitmap = null;
                for (Method m : maskUtils.getDeclaredMethods()) {
                    if ("I1".equals(m.getName()) && m.getParameterCount() == 1) {
                        maskToBitmap = m;
                        break;
                    }
                }
                Method getParamJ = iconParamUtils.getMethod("j");
                Method getParamH = iconParamUtils.getMethod("h");
                final Method calcMaskDataM = calcMaskData;
                final Method maskToBitmapM = maskToBitmap;
                if (calcMaskDataM != null && maskToBitmapM != null) {
                    interceptMethods(folderIconUtils, "W", chain -> {
                        List<Object> args = chain.getArgs();
                        if (args.size() >= 4 && args.get(0) instanceof Integer) {
                            int morphState = (Integer) args.get(0);
                            if ((morphState & CUSTOM_STATE_MASK) == CUSTOM_STATE_TAG) {
                                int spanX = (morphState >> 8) & 0xFF;
                                int spanY = morphState & 0xFF;
                                Object utils = getLauncherMaskUtils.invoke(null);
                                Object maskData = calcMaskDataM.invoke(
                                        utils, spanX, spanY, args.get(1),
                                        getParamJ.invoke(null), args.get(2),
                                        getParamH.invoke(null), args.get(3));
                                Object bmp = maskToBitmapM.invoke(null, maskData);
                                if (bmp != null) return bmp;
                            }
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            hookError("hookFolderBitmapLookup", t);
        }

        final Set<Object> animatingPagedViews = Collections.synchronizedSet(
                Collections.newSetFromMap(new WeakHashMap<>()));
        final Map<Object, List<ClipSnapshot>> savedClipStates = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, List<View>> closeRevealViews = Collections.synchronizedMap(new WeakHashMap<>());

        try {
            Class<?> pagedViewCls = Class.forName(CLS_PAGED_VIEW, false, cl);
            if (markInstalled(pagedViewCls)) {
                Method getVisibleRange = pagedViewCls.getMethod("Y5", int[].class);
                Method dispatchDraw = pagedViewCls.getDeclaredMethod("dispatchDraw", Canvas.class);
                Method drawChild = ViewGroup.class.getDeclaredMethod("drawChild", Canvas.class, View.class, long.class);
                drawChild.setAccessible(true);

                intercept(dispatchDraw, chain -> {
                    Object self = chain.getThisObject();
                    if (self instanceof ViewGroup && animatingPagedViews.contains(self)) {
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) instanceof Canvas) {
                            Canvas canvas = (Canvas) args.get(0);
                            ViewGroup vg = (ViewGroup) self;
                            int[] range = new int[2];
                            getVisibleRange.invoke(self, range);
                            int count = vg.getChildCount();
                            if (count > 0 && (range[0] != -1 || range[1] != -1)) {
                                int start = Math.max(0, range[0]);
                                int end = Math.min(count - 1, range[1]);
                                long dt = vg.getDrawingTime();
                                for (int i = end; i >= start; i--) {
                                    View child = vg.getChildAt(i);
                                    if (child != null) {
                                        drawChild.invoke(self, canvas, child, dt);
                                    }
                                }
                            }
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            hookError("hookPagedViewUnclip", t);
        }

        try {
            Class<?> animCls = Class.forName(CLS_OPEN_CLOSE_ANIM_HELPER, false, cl);
            if (markInstalled(animCls)) {
                Field fMain = animCls.getField("b");
                Field fPaged = animCls.getField("c");
                Field fPreviewHolder = animCls.getField("l0");
                Field fAnimList = animCls.getField("m");
                Field fAnimView = Class.forName(CLS_OPEN_CLOSE_ANIM_HELPER + "$u", false, cl).getField("a");

                Class<?> cellLayoutCls = Class.forName(CLS_CELL_LAYOUT, false, cl);
                Method getShortcutContainer = cellLayoutCls.getMethod("getShortcutAndWidgetContainer");
                Method getAnimProgress = fMain.getType().getMethod("getAnimProgress");
                Method getFolderIcon = fMain.getType().getMethod("getOriginFolderIcon");
                Method getIconContainer = Class.forName(CLS_FOLDER_ICON, false, cl).getMethod("getContainerForIconInFolder");
                Class<?> itemIconCls = Class.forName(CLS_ITEM_ICON_VIEW, false, cl);
                Class<?> previewCls = Class.forName(CLS_FOLDER_PREVIEW, false, cl);
                Method getRank = itemIconCls.getMethod("getRank");

                interceptMethods(animCls, "u0", chain -> {
                    Object res = chain.proceed();
                    Object self = chain.getThisObject();
                    List<Object> args = chain.getArgs();
                    if (self == null) return res;

                    Object holder = fPreviewHolder.get(self);
                    boolean holderAnimated = false;
                    List<?> anims = (List<?>) fAnimList.get(self);
                    if (holder instanceof View && anims != null) {
                        for (Object entry : anims) {
                            if (entry != null && fAnimView.get(entry) == holder) {
                                holderAnimated = true;
                                break;
                            }
                        }
                    }
                    if (holder instanceof View && !holderAnimated) {
                        ((View) holder).setVisibility(View.GONE);
                    }

                    boolean isCustom = !args.isEmpty() && args.get(0) instanceof Integer
                            && (((Integer) args.get(0)) & CUSTOM_STATE_MASK) == CUSTOM_STATE_TAG;
                    if (!isCustom) return res;

                    unclipFolderHierarchy(self, fMain, fPaged, cellLayoutCls, getShortcutContainer,
                            animatingPagedViews, savedClipStates);

                    boolean isOpen = args.size() >= 2 && Boolean.TRUE.equals(args.get(1));
                    if (!isOpen) {
                        List<View> extra = collectCloseRevealViews(
                                fMain.get(self), getFolderIcon, getIconContainer,
                                itemIconCls, previewCls, getRank, holderAnimated);
                        if (!extra.isEmpty()) closeRevealViews.put(self, extra);
                        else closeRevealViews.remove(self);
                    } else {
                        closeRevealViews.remove(self);
                    }
                    return res;
                });

                interceptMethods(animCls, "g0", chain -> {
                    Object res = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self == null) return res;
                    List<View> extra = closeRevealViews.get(self);
                    if (extra != null && !extra.isEmpty()) {
                        Object main = fMain.get(self);
                        if (main != null) {
                            float p = ((Number) getAnimProgress.invoke(main)).floatValue();
                            float alpha = Math.max(0.0f, Math.min(1.0f, (p - 0.15f) / 0.55f));
                            for (View v : extra) v.setAlpha(alpha);
                        }
                    }
                    return res;
                });

                Hooker finishCleanup = chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        closeRevealViews.remove(self);
                        try {
                            restoreFolderHierarchyClip(self, fPaged, animatingPagedViews, savedClipStates);
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                };
                for (String endMethod : new String[]{"M0", "P0", "Q0", "p1", "N0"}) {
                    interceptMethods(animCls, endMethod, finishCleanup);
                }
            }
        } catch (Throwable t) {
            hookError("hookOpenClosePreviewHolder", t);
        }
    }

    private static final class ClipSnapshot {
        final ViewGroup group;
        final boolean clipChildren;
        final boolean clipToPadding;

        ClipSnapshot(ViewGroup group) {
            this.group = group;
            this.clipChildren = group.getClipChildren();
            this.clipToPadding = group.getClipToPadding();
        }

        void restore() {
            group.setClipChildren(clipChildren);
            group.setClipToPadding(clipToPadding);
        }
    }

    private static void unclipGroup(ViewGroup vg, List<ClipSnapshot> out, Set<ViewGroup> seen) {
        if (vg != null && seen.add(vg)) {
            out.add(new ClipSnapshot(vg));
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
        }
    }

    /**
     * Disables child and padding clipping along the full open-folder hierarchy — from every
     * {@code ShortcutAndWidgetContainer} page up through {@code OriginFolderPagedView}, intermediate
     * wrapper containers such as {@code OriginFolderMain.d}, {@code OriginFolderMain}, and its
     * immediate parent ({@code DragLayer}) — while saving original flags for restoration on end.
     */
    private static void unclipFolderHierarchy(
            Object animHelper, Field fMain, Field fPaged, Class<?> cellLayoutCls,
            Method getShortcutContainer, Set<Object> animatingPagedViews,
            Map<Object, List<ClipSnapshot>> savedClipStates) throws Throwable {
        if (savedClipStates.containsKey(animHelper)) {
            restoreFolderHierarchyClip(animHelper, fPaged, animatingPagedViews, savedClipStates);
        }

        List<ClipSnapshot> snapshots = new ArrayList<>();
        Set<ViewGroup> seen = new HashSet<>();
        Object main = fMain.get(animHelper);
        Object paged = fPaged.get(animHelper);

        if (paged instanceof ViewGroup) {
            ViewGroup pagedVg = (ViewGroup) paged;
            animatingPagedViews.add(paged);
            for (int i = 0; i < pagedVg.getChildCount(); i++) {
                View page = pagedVg.getChildAt(i);
                if (page instanceof ViewGroup) {
                    unclipGroup((ViewGroup) page, snapshots, seen);
                    if (cellLayoutCls.isInstance(page)) {
                        Object sc = getShortcutContainer.invoke(page);
                        if (sc instanceof ViewGroup) {
                            unclipGroup((ViewGroup) sc, snapshots, seen);
                        }
                    }
                }
            }

            View current = pagedVg;
            boolean reachedMain = (current == main);
            while (current != null) {
                if (current instanceof ViewGroup) {
                    unclipGroup((ViewGroup) current, snapshots, seen);
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof ViewGroup)) break;
                if (reachedMain) {
                    unclipGroup((ViewGroup) parent, snapshots, seen);
                    break;
                }
                if (parent == main) reachedMain = true;
                current = (View) parent;
            }
        }

        if (main instanceof ViewGroup) {
            ViewGroup mainVg = (ViewGroup) main;
            unclipGroup(mainVg, snapshots, seen);
            ViewParent mainParent = mainVg.getParent();
            if (mainParent instanceof ViewGroup) {
                unclipGroup((ViewGroup) mainParent, snapshots, seen);
            }
        }

        if (!snapshots.isEmpty()) {
            savedClipStates.put(animHelper, snapshots);
        }
    }

    private static void restoreFolderHierarchyClip(
            Object animHelper, Field fPaged, Set<Object> animatingPagedViews,
            Map<Object, List<ClipSnapshot>> savedClipStates) throws Throwable {
        Object paged = fPaged.get(animHelper);
        if (paged != null) animatingPagedViews.remove(paged);
        List<ClipSnapshot> snapshots = savedClipStates.remove(animHelper);
        if (snapshots != null) {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                snapshots.get(i).restore();
            }
        }
    }

    private static List<View> collectCloseRevealViews(
            Object main, Method getFolderIcon, Method getIconContainer,
            Class<?> itemIconCls, Class<?> previewCls, Method getRank,
            boolean previewAnimated) throws Throwable {
        List<View> out = new ArrayList<>();
        if (main == null) return out;
        Object folderIcon = getFolderIcon.invoke(main);
        if (folderIcon == null) return out;
        Object container = getIconContainer.invoke(folderIcon);
        if (!(container instanceof ViewGroup)) return out;
        ViewGroup vg = (ViewGroup) container;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child == null) continue;
            if (itemIconCls.isInstance(child)) {
                int rank = (Integer) getRank.invoke(child);
                if (rank >= 12) out.add(child);
            } else if (previewCls.isInstance(child) && !previewAnimated) {
                out.add(child);
            }
        }
        return out;
    }

    private static boolean markInstalled(Class<?> cls) {
        synchronized (INSTALLED) {
            return INSTALLED.add(cls);
        }
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }

    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}
