package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.DockingDesktopManager;
import com.inductiveautomation.factorypmi.application.FPMIWindow;
import com.inductiveautomation.factorypmi.application.VisionDesktop;
import com.inductiveautomation.factorypmi.application.components.util.FPMILayout;
import com.inductiveautomation.factorypmi.application.components.util.FPMI_LC;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shrinks the whole client evenly when its window is smaller than full screen, like a picture.
 *
 * <p>Vision shrinks a window's relative components, but docked windows (a title bar, a menu) keep their pixel size,
 * and anchored components (a header bar or tab strip pinned to an edge) keep theirs too, so in a small window the
 * docks take most of the space and anchored parts overlap the shrinking rest. While the client is smaller than
 * its screen's full size, this scales each docked window's thickness by the same factor (Vision's dock layout uses
 * each docked window's current width or height) and lays anchored components out as relative ones (their layout
 * flags), and puts both back at full screen. A maximized client is never changed; the project isn't either.
 */
final class ClientScale {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");
    static final double FULL = 0.97;
    /** A container's aspect-ratio flags: what Vision does with a window of another shape. */
    private static final int AR_MASK = FPMILayout.AR_LEADING | FPMILayout.AR_TRAILING | FPMILayout.AR_OFF;
    /** Popup -> its own size, and the size this module gave it. */
    private static final Map<FPMIWindow, Dimension> popupSize = new WeakHashMap<>();
    private static final Map<FPMIWindow, Dimension> popupApplied = new WeakHashMap<>();
    private static final int ANCHORS = FPMILayout.ANCHOR_N | FPMILayout.ANCHOR_S | FPMILayout.ANCHOR_E
            | FPMILayout.ANCHOR_W | FPMILayout.CENTER_X | FPMILayout.CENTER_Y;

    /** Docked window -> its own thickness (height when docked north/south, width when east/west). */
    private static final Map<FPMIWindow, Integer> designThickness = new WeakHashMap<>();
    /** Anchored component -> its original flags and font ratios, while it is laid out as relative. */
    private static final Map<JComponent, Saved> saved = new WeakHashMap<>();
    private static boolean busy;

    private static final class Saved {
        final Integer flags;
        final Float ratioX;
        final Float ratioY;
        final java.awt.Font font;

        Saved(JComponent c, FPMI_LC lc) {
            flags = lc.getFlags();
            ratioX = lc.getFontRatioX();
            ratioY = lc.getFontRatioY();
            font = c.getFont();
        }

        /** Back to the anchored layout, with the font it had (Vision doesn't rescale fonts without ratios). */
        void restore(JComponent c, FPMI_LC lc) {
            int now = lc.getFlags() == null ? 0 : lc.getFlags();
            lc.setFlags((flags & ~AR_MASK) | (now & AR_MASK));
            lc.setFontRatioX(ratioX);
            lc.setFontRatioY(ratioY);
            if (font != null && (ratioX == null || ratioY == null)) {
                c.setFont(font);
            }
        }
    }

    private ClientScale() {
    }

    /** The client's size as a fraction of its full-screen size (at most 1). */
    static double scaleOf(VisionDesktop desktop) {
        Window w = SwingUtilities.getWindowAncestor(desktop);
        if (w == null || !w.isShowing()) {
            return 1;
        }
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, desktop);
        Dimension visible = sp != null ? sp.getViewport().getExtentSize() : desktop.getSize();
        if (visible.width <= 0 || visible.height <= 0) {
            return 1;
        }
        GraphicsConfiguration gc = w.getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets si = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        // Full size: the screen's work area minus what the window itself takes (frame, menu bar, banners).
        double fullW = screen.width - si.left - si.right - (w.getWidth() - visible.width);
        double fullH = screen.height - si.top - si.bottom - (w.getHeight() - visible.height);
        if (fullW <= 0 || fullH <= 0) {
            return 1;
        }
        return Math.min(1, Math.min(visible.width / fullW, visible.height / fullH));
    }

    private static final java.util.Set<VisionDesktop> scheduled = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** Updates a desktop after the current layout pass, once however many events ask for it. */
    static void schedule(VisionDesktop desktop) {
        if (desktop != null && scheduled.add(desktop)) {
            SwingUtilities.invokeLater(() -> {
                scheduled.remove(desktop);
                update(desktop);
            });
        }
    }

    static void update(VisionDesktop desktop) {
        if (busy || desktop == null) {
            return;
        }
        busy = true;
        try {
            double s = scaleOf(desktop);
            boolean small = s < FULL;
            boolean docks = small && Settings.on(Settings.Fix.DOCKS);
            boolean anchored = small && Settings.on(Settings.Fix.ANCHORED);
            boolean popups = small && Settings.on(Settings.Fix.POPUPS);
            Rectangle area = freeArea(desktop);
            boolean changed = false;
            for (JInternalFrame f : desktop.getAllFrames()) {
                if (!(f instanceof FPMIWindow)) {
                    continue;
                }
                FPMIWindow win = (FPMIWindow) f;
                changed |= dock(win, docks ? s : 1);
                changed |= anchors(win, anchored);
                changed |= popup(win, area, popups);
                if (win.getRootContainer() != null) {
                    walk(win.getRootContainer(), c -> {
                        if (InnerParts.handles(c)) {
                            SwingUtilities.invokeLater(() -> InnerParts.fit(c));
                        }
                    });
                }
            }
            if (changed) {
                if (desktop.getDesktopManager() instanceof DockingDesktopManager) {
                    ((DockingDesktopManager) desktop.getDesktopManager()).layoutDocks();
                }
                desktop.revalidate();
                desktop.repaint();
            }
        } catch (Throwable t) {
            LOG.debug("Could not scale the client", t);
        } finally {
            busy = false;
        }
    }

    /** Sets a docked window's thickness to its own times the factor; returns whether it changed. */
    private static boolean dock(FPMIWindow win, double factor) {
        int pos = win.getDockPosition();
        if (pos == FPMIWindow.DOCK_FLOAT) {
            return false;
        }
        boolean vertical = pos == FPMIWindow.DOCK_NORTH || pos == FPMIWindow.DOCK_SOUTH;
        Integer design = designThickness.get(win);
        if (design == null) {
            design = vertical ? win.getHeight() : win.getWidth();
            if (design <= 0) {
                return false;
            }
            designThickness.put(win, design);
        }
        int target = Math.max(1, (int) Math.round(design * factor));
        int current = vertical ? win.getHeight() : win.getWidth();
        if (current == target) {
            return false;
        }
        if (vertical) {
            win.setSize(win.getWidth(), target);
        } else {
            win.setSize(target, win.getHeight());
        }
        return true;
    }

    /** Lays a window's anchored components out as relative ones (on) or as before (off). */
    private static boolean anchors(FPMIWindow win, boolean on) {
        Container root = win.getRootContainer();
        if (root == null) {
            return false;
        }
        boolean[] changed = {false};
        walk(root, c -> {
            FPMI_LC lc = FitText.constraints(c);
            if (lc == null || lc.getFlags() == null) {
                return;
            }
            Saved was = saved.get(c);
            if (on && was == null && (lc.getFlags() & FPMILayout.SCALE_RELATIVE) == 0) {
                Rectangle2D design = FPMILayout.getPreferredBounds(c);
                saved.put(c, new Saved(c, lc));
                lc.setFlags((lc.getFlags() & ~ANCHORS) | FPMILayout.SCALE_RELATIVE);
                // Relative components scale their font with them; anchored ones may have no font ratios yet.
                if ((lc.getFontRatioX() == null || lc.getFontRatioY() == null) && c.getFont() != null
                        && design != null && design.getWidth() > 0 && design.getHeight() > 0
                        && !Boolean.TRUE.equals(lc.getDisableFontScale())) {
                    float size = c.getFont().getSize2D();
                    lc.setFontRatioX((float) (size / design.getWidth()));
                    lc.setFontRatioY((float) (size / design.getHeight()));
                }
                invalidate(c);
                changed[0] = true;
            } else if (!on && was != null) {
                saved.remove(c);
                FitText.release(c);
                was.restore(c, lc);
                invalidate(c);
                changed[0] = true;
            }
        });
        if (changed[0]) {
            root.revalidate();
            root.repaint();
        }
        return changed[0];
    }

    private static void invalidate(JComponent c) {
        c.invalidate();
        if (c.getParent() != null) {
            c.getParent().invalidate();
        }
    }

    interface Visitor {
        void visit(JComponent c);
    }

    static void walk(Container parent, Visitor v) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComponent) {
                v.visit((JComponent) child);
            }
            if (child instanceof Container) {
                walk((Container) child, v);
            }
        }
    }

    /** The desktop area left for screens and popups: the maximized main window's bounds, else what is visible. */
    private static Rectangle freeArea(VisionDesktop desktop) {
        // What is visible of the desktop (Vision grows the desktop around a popup that doesn't fit, so the
        // desktop and the main window can be larger than the client window).
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, desktop);
        Rectangle view = sp != null ? sp.getViewport().getViewRect() : new Rectangle(desktop.getSize());
        for (JInternalFrame f : desktop.getAllFrames()) {
            if (f instanceof FPMIWindow && f.isVisible() && ((FPMIWindow) f).getDockPosition() != FPMIWindow.DOCK_FLOAT) {
                // Leave out the docked windows.
                Rectangle d = f.getBounds();
                switch (((FPMIWindow) f).getDockPosition()) {
                    case FPMIWindow.DOCK_NORTH: { int top = Math.max(view.y, d.y + d.height); view.height -= top - view.y; view.y = top; break; }
                    case FPMIWindow.DOCK_SOUTH: view.height = Math.min(view.height, d.y - view.y); break;
                    case FPMIWindow.DOCK_WEST: { int left = Math.max(view.x, d.x + d.width); view.width -= left - view.x; view.x = left; break; }
                    case FPMIWindow.DOCK_EAST: view.width = Math.min(view.width, d.x - view.x); break;
                    default: break;
                }
            }
        }
        return view;
    }

    /** Popups: a floating window bigger than the free area shrinks to fit it (centered), and gets its size back. */
    private static boolean popup(FPMIWindow win, Rectangle area, boolean on) {
        if (win.getDockPosition() != FPMIWindow.DOCK_FLOAT || win.isMaximum() || !win.isVisible()) {
            return false;
        }
        Dimension now = win.getSize();
        if (now.width <= 0 || now.height <= 0) {
            return false;   // not sized by Vision yet; it is fitted on its next resize
        }
        Dimension applied = popupApplied.get(win);
        if (applied != null && !applied.equals(now)) {
            popupApplied.remove(win);      // resized since (by the user or the project): that is its size now
            popupSize.put(win, now);
            applied = null;
        }
        Dimension own = popupSize.computeIfAbsent(win, w -> new Dimension(now));
        if (own.width <= 0 || own.height <= 0 || area.width <= 0 || area.height <= 0) {
            return false;
        }
        double fit = Math.min(1, Math.min(area.width / (double) own.width, area.height / (double) own.height));
        if (on && fit < 0.99) {
            Dimension target = new Dimension(Math.max(60, (int) Math.floor(own.width * fit)), Math.max(40, (int) Math.floor(own.height * fit)));
            if (target.equals(now)) {
                return false;
            }
            boolean first = applied == null;
            win.setSize(target);
            if (first) {
                // The first time: centered in the visible area, as Vision centers a popup it opens.
                win.setLocation(area.x + Math.max(0, (area.width - target.width) / 2), area.y + Math.max(0, (area.height - target.height) / 2));
            } else {
                keepInside(win, area);   // where the operator put it, still in view
            }
            popupApplied.put(win, target);
            return true;
        }
        if (applied != null) {
            popupApplied.remove(win);
            win.setSize(own);
            keepInside(win, area);
            return true;
        }
        return false;
    }

    /** Moves a window only as far as needed to keep it inside the area. */
    private static void keepInside(FPMIWindow win, Rectangle area) {
        int x = Math.max(area.x, Math.min(win.getX(), area.x + area.width - win.getWidth()));
        int y = Math.max(area.y, Math.min(win.getY(), area.y + area.height - win.getHeight()));
        if (x != win.getX() || y != win.getY()) {
            win.setLocation(x, y);
        }
    }

    /** Back to Vision's own layout everywhere (module removed). */
    static void restoreAll() {
        for (FPMIWindow win : new ArrayList<>(designThickness.keySet())) {
            dock(win, 1);
        }
        for (Map.Entry<JComponent, Saved> e : new ArrayList<>(saved.entrySet())) {
            FPMI_LC lc = FPMILayout.getLayoutConstraints(e.getKey());
            if (lc != null) {
                e.getValue().restore(e.getKey(), lc);
                invalidate(e.getKey());
            }
        }
        saved.clear();
        designThickness.clear();
        for (Map.Entry<FPMIWindow, Dimension> e : new ArrayList<>(popupApplied.entrySet())) {
            Dimension own = popupSize.get(e.getKey());
            if (own != null && e.getValue().equals(e.getKey().getSize())) {
                e.getKey().setSize(own);
            }
        }
        popupApplied.clear();
        popupSize.clear();
    }
}
