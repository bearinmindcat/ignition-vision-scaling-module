package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.FPMIWindow;
import com.inductiveautomation.factorypmi.application.components.util.FPMILayout;
import com.inductiveautomation.factorypmi.application.components.util.FPMI_LC;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps numbers and labels whole when Vision draws a window smaller than its design size.
 *
 * <p>Vision's layout (FPMILayout) shrinks each component's box and font together, rounding the font to a whole
 * point size, but never the component's border. Ignition's look and feel gives a text field 8 px of border and
 * margin in height, so at half screen a 12 px value box has 4 px left for 11 px of text and only the middle of the
 * digits shows; labels whose font was rounded up end in "...".
 *
 * <p>After Vision lays a component out (it is resized, or its font, text or value changes), this gives a text
 * field a border whose margins shrink with the box, and gives text fields, labels and buttons the exact
 * (fractional) font size, shrunk until the text fits the room it has.
 *
 * <p>Vision re-applies its own rounded font on every layout pass, but only when the rounded size differs from the
 * component's current one (FPMILayout.scaleFont). So the chosen size is also written into the component's font
 * ratios, and Vision's next pass arrives at the same size and leaves the font alone (changing only the font made
 * the two undo each other in a loop). The original ratios are kept and restored at design size. Components at
 * (nearly) their design size are left exactly as Vision draws them; only Vision client windows are touched, and
 * nothing is saved to the project.
 */
public final class FitText {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");

    /** Below this fraction of its design height a component counts as shrunk; at or above it Vision's own look stays. */
    static final double SHRUNK = 0.97;
    private static final float MIN_FONT = 2f;
    private static final float STEP = 0.25f;
    private static final Color LINE = new Color(0xB4, 0xB9, 0xBE);

    private static AWTEventListener listener;
    /** Per component: Vision's own font ratios, the border it had, and the last state fitted. */
    private static final Map<JComponent, State> states = new WeakHashMap<>();
    private static final Set<JComponent> pending = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean scheduled;
    private static boolean applying;

    private static final PropertyChangeListener WATCHER = event -> {
        if (!applying && event.getSource() instanceof JComponent) {
            queue((JComponent) event.getSource());
        }
    };

    private static final class State {
        final float ratioX;
        final float ratioY;
        Border border;           // the border Vision gave a text field, while ours replaces it
        String key;              // width, height, text and font size last fitted
        long windowStart;        // loop breaker: fits counted per second
        int fitsInWindow;
        long pausedUntil;

        State(float ratioX, float ratioY) {
            this.ratioX = ratioX;
            this.ratioY = ratioY;
        }
    }

    private FitText() {
    }

    public static synchronized void install() {
        if (Boolean.getBoolean("visionscaling.disabled")) {
            LOG.info("Vision Scaling Fix is turned off (-Dvisionscaling.disabled=true).");
            return;
        }
        if (listener != null) {
            return;
        }
        listener = event -> {
            try {
                int id = event.getID();
                if ((id == ComponentEvent.COMPONENT_RESIZED || id == ComponentEvent.COMPONENT_SHOWN)
                        && event.getSource() instanceof JComponent) {
                    queue((JComponent) event.getSource());
                }
            } catch (Throwable t) {
                LOG.debug("Event handling failed", t);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.COMPONENT_EVENT_MASK);
        LOG.info("Vision Scaling Fix installed.");
    }

    public static synchronized void uninstall() {
        if (listener == null) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        listener = null;
        SwingUtilities.invokeLater(FitText::restoreAll);
        LOG.info("Vision Scaling Fix removed.");
    }

    /** Collects components and fits them once, after the layout pass that changed them (on the event thread). */
    private static void queue(JComponent c) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> queue(c));
            return;
        }
        pending.add(c);
        if (!scheduled) {
            scheduled = true;
            SwingUtilities.invokeLater(FitText::drain);
        }
    }

    private static void drain() {
        scheduled = false;
        List<JComponent> batch = new ArrayList<>(pending);
        pending.clear();
        for (JComponent c : batch) {
            try {
                fit(c);
            } catch (Throwable t) {
                LOG.debug("Could not fit {}", c.getName(), t);
            }
        }
    }

    static void fit(JComponent c) {
        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0 || c.getFont() == null) {
            return;
        }
        boolean field = c instanceof JTextField;
        if (!field && !(c instanceof JLabel) && !(c instanceof AbstractButton)) {
            return;
        }
        String text = text(c);
        if (!field && (text == null || text.isEmpty() || text.regionMatches(true, 0, "<html", 0, 5))) {
            return;  // HTML wraps instead of ending in "..."; empty text has nothing to fit.
        }
        if (SwingUtilities.getAncestorOfClass(FPMIWindow.class, c) == null) {
            return;
        }
        FPMI_LC lc = FPMILayout.getLayoutConstraints(c);
        if (lc == null || lc.getFontRatioX() == null || lc.getFontRatioY() == null
                || Boolean.TRUE.equals(lc.getDisableFontScale())) {
            return;  // Vision doesn't scale this component's font.
        }
        Rectangle2D design = FPMILayout.getPreferredBounds(c);
        if (design == null || design.getHeight() <= 0) {
            return;
        }
        State state = states.get(c);
        if (state == null) {
            state = new State(lc.getFontRatioX(), lc.getFontRatioY());
            states.put(c, state);
            c.addPropertyChangeListener("font", WATCHER);
            c.addPropertyChangeListener("text", WATCHER);
            c.addPropertyChangeListener("value", WATCHER);
        }
        String key = w + "x" + h + "|" + c.getFont().getSize2D() + "|" + text;
        if (key.equals(state.key)) {
            return;  // Nothing changed since the last fit.
        }
        long now = System.currentTimeMillis();
        if (now < state.pausedUntil) {
            return;
        }
        if (now - state.windowStart > 1000) {
            state.windowStart = now;
            state.fitsInWindow = 0;
        }
        if (++state.fitsInWindow > 5) {
            // Something keeps changing it back (another script, a styles table): leave it alone for a while.
            state.pausedUntil = now + 5000;
            LOG.debug("Pausing fits for {}: changed back too often", c.getName());
            return;
        }
        // Vision's font arithmetic uses the fractional layout bounds, not the whole-pixel size.
        Rectangle2D bounds = FPMILayout.getBounds(c);
        double bw = bounds != null && bounds.getWidth() > 0 ? bounds.getWidth() : w;
        double bh = bounds != null && bounds.getHeight() > 0 ? bounds.getHeight() : h;
        double scale = bh / design.getHeight();
        applying = true;
        try {
            if (scale >= SHRUNK) {
                restore(c, lc, state);
            } else {
                shrink(c, lc, state, field, text, w, h, bw, bh, design.getHeight());
            }
            state.key = w + "x" + h + "|" + c.getFont().getSize2D() + "|" + text(c);
        } finally {
            applying = false;
        }
    }

    private static void shrink(JComponent c, FPMI_LC lc, State state, boolean field, String text, int w, int h,
                               double bw, double bh, double designHeight) {
        if (field) {
            if (state.border == null) {
                state.border = c.getBorder();
            }
            int pad = (int) Math.round(3 * (bh / designHeight));
            Border ours = new CompoundBorder(new LineBorder(LINE, 1), new EmptyBorder(pad, pad + 1, pad, pad + 1));
            if (!(c.getBorder() instanceof CompoundBorder && ((CompoundBorder) c.getBorder()).getInsideBorder() instanceof EmptyBorder
                    && Objects.equals(c.getBorder().getBorderInsets(c), ours.getBorderInsets(c)))) {
                c.setBorder(ours);
            }
        }
        // Vision's size before rounding: the smaller of the width and height ratios (FPMILayout.scaleFont).
        float size = (float) Math.max(MIN_FONT, Math.min(state.ratioX * bw, state.ratioY * bh));
        Insets in = c.getInsets();
        int room = h - in.top - in.bottom;
        int widthRoom = w - in.left - in.right - iconWidth(c);
        Font base = c.getFont();
        while (size > MIN_FONT && !fits(c, base.deriveFont(size), text, room, widthRoom)) {
            size -= STEP;
        }
        // Off the half-point: Vision rounds ratio x bounds (Math.round) and compares it with the font's whole size
        // ((int) (size + 0.5)); right at x.5 float error could make the two disagree.
        float frac = size - (float) Math.floor(size);
        if (frac > 0.48f && frac < 0.52f) {
            size -= 0.03f;
        }
        // Vision's next layout pass then computes the same (rounded) size and keeps this font.
        lc.setFontRatioX((float) (size / bw));
        lc.setFontRatioY((float) (size / bh));
        if (Math.abs(base.getSize2D() - size) > 0.01f) {
            c.setFont(base.deriveFont(size));
        }
    }

    /** Back to Vision's own look: its ratios, its border and its rounded font size for the current box. */
    private static void restore(JComponent c, FPMI_LC lc, State state) {
        lc.setFontRatioX(state.ratioX);
        lc.setFontRatioY(state.ratioY);
        if (state.border != null) {
            c.setBorder(state.border);
            state.border = null;
        }
        // Vision's next layout pass sets its own rounded font from the restored ratios.
        c.revalidate();
    }

    private static boolean fits(JComponent c, Font font, String text, int room, int widthRoom) {
        FontMetrics fm = c.getFontMetrics(font);
        // One pixel of slack: digits don't reach the bottom of the line (descent).
        if (fm.getAscent() + fm.getDescent() > room + 1) {
            return false;
        }
        return text == null || text.isEmpty() || fm.stringWidth(text) <= widthRoom;
    }

    private static String text(JComponent c) {
        if (c instanceof JTextField) {
            return ((JTextField) c).getText();
        }
        if (c instanceof JLabel) {
            return ((JLabel) c).getText();
        }
        return ((AbstractButton) c).getText();
    }

    private static int iconWidth(JComponent c) {
        Icon icon = null;
        int gap = 0;
        if (c instanceof JLabel) {
            icon = ((JLabel) c).getIcon();
            gap = ((JLabel) c).getIconTextGap();
        } else if (c instanceof AbstractButton) {
            icon = ((AbstractButton) c).getIcon();
            gap = ((AbstractButton) c).getIconTextGap();
        }
        return icon == null ? 0 : icon.getIconWidth() + gap;
    }

    private static void restoreAll() {
        applying = true;
        try {
            for (Map.Entry<JComponent, State> e : new ArrayList<>(states.entrySet())) {
                JComponent c = e.getKey();
                c.removePropertyChangeListener("font", WATCHER);
                c.removePropertyChangeListener("text", WATCHER);
                c.removePropertyChangeListener("value", WATCHER);
                FPMI_LC lc = FPMILayout.getLayoutConstraints(c);
                if (lc != null) {
                    restore(c, lc, e.getValue());
                }
                c.revalidate();
            }
            states.clear();
        } finally {
            applying = false;
        }
    }
}
