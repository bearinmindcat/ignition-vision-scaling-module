package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.FPMIWindow;
import com.inductiveautomation.factorypmi.application.VisionDesktop;
import com.inductiveautomation.factorypmi.application.components.util.FPMILayout;
import com.inductiveautomation.factorypmi.application.components.util.FPMI_LC;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
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
    /** Text drawn smaller than this (points) gets a tooltip showing it at a readable size ("Read tiny text"). */
    private static final float TINY = 7f;

    private static AWTEventListener listener;
    private static final KeyEventDispatcher HOTKEY = e -> {
        // Ctrl+Period opens the settings window.
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_PERIOD && e.isControlDown()
                && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown()) {
            SettingsDialog.open();
            return true;
        }
        return false;
    };
    private static final Runnable ON_CHANGE = () -> SwingUtilities.invokeLater(FitText::refresh);
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
        Insets margin;           // a button's own margin, while a scaled one replaces it
        boolean tip;             // the tooltip is ours ("Read tiny text")
        String key;              // width, height, text and font size last fitted
        String size;             // width, height and text of the last fit (loop breaker)
        long windowStart;        // loop breaker: same-size refits counted per second
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
                if (id == ComponentEvent.COMPONENT_RESIZED || id == ComponentEvent.COMPONENT_SHOWN) {
                    Object source = event.getSource();
                    if (source instanceof Window) {
                        // The desktop stops getting resize events once it's at its minimum, but its window doesn't.
                        MinimumSize.scan((Window) source);
                    } else if (source instanceof JComponent) {
                        if (source instanceof FPMIWindow) {
                            ClientScale.schedule((VisionDesktop) SwingUtilities.getAncestorOfClass(VisionDesktop.class, (FPMIWindow) source));
                        }
                        queue((JComponent) source);
                    }
                }
            } catch (Throwable t) {
                LOG.debug("Event handling failed", t);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.COMPONENT_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(HOTKEY);
        Settings.addListener(ON_CHANGE);
        SwingUtilities.invokeLater(() -> {
            try {
                MinimumSize.scanAll();
            } catch (Throwable t) {
                LOG.debug("Could not lower the minimum size", t);
            }
        });
        LOG.info("Vision Scaling Fix installed.");
    }

    public static synchronized void uninstall() {
        if (listener == null) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(HOTKEY);
        Settings.removeListener(ON_CHANGE);
        listener = null;
        SwingUtilities.invokeLater(FitText::restoreAll);
        LOG.info("Vision Scaling Fix removed.");
    }

    /** Collects components and fits them once, after the layout pass that changed them (on the event thread). */
    /** A component's Vision layout constraints, or null for parts Vision doesn't lay out (inside a table, ...). */
    static FPMI_LC constraints(JComponent c) {
        Object lc = c.getClientProperty(FPMILayout.LAYOUT_CONSTRAINTS);
        return lc instanceof FPMI_LC ? (FPMI_LC) lc : null;
    }

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
        if (InnerParts.handles(c)) {
            InnerParts.fit(c);
            return;
        }
        boolean field = c instanceof JTextField;
        if (!field && !(c instanceof JLabel) && !(c instanceof AbstractButton)) {
            return;
        }
        Settings.Fix kind = field ? Settings.Fix.FIELDS : c instanceof JLabel ? Settings.Fix.LABELS : Settings.Fix.BUTTONS;
        if (!Settings.on(kind)) {
            release(c);
            return;
        }
        String text = text(c);
        if (!field && (text == null || text.isEmpty() || text.regionMatches(true, 0, "<html", 0, 5))) {
            return;  // HTML wraps instead of ending in "..."; empty text has nothing to fit.
        }
        if (SwingUtilities.getAncestorOfClass(FPMIWindow.class, c) == null) {
            return;
        }
        FPMI_LC lc = constraints(c);
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
        String size = w + "x" + h + "|" + text;
        if (!size.equals(state.size)) {
            // A new size or text is normal (the window is being resized): not a loop.
            state.size = size;
            state.fitsInWindow = 0;
            state.windowStart = now;
        } else {
            if (now - state.windowStart > 1000) {
                state.windowStart = now;
                state.fitsInWindow = 0;
            }
            if (++state.fitsInWindow > 5) {
                // Something keeps changing it back at the same size (a script, a styles table): pause, then retry.
                state.pausedUntil = now + 5000;
                LOG.debug("Pausing fits for {}: changed back too often", c.getName());
                javax.swing.Timer retry = new javax.swing.Timer(5100, e -> {
                    State st = states.get(c);
                    if (st != null) {
                        st.key = null;
                        st.size = null;
                    }
                    queue(c);
                });
                retry.setRepeats(false);
                retry.start();
                return;
            }
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
        if (c instanceof AbstractButton && (state.border != null || c.getBorder() != null)) {
            // Ignition's look and feel keeps 6 px of button border on every side at any size; a 21 px button has
            // 9 px left for its text. Paint the same border, but with insets that shrink with the button.
            if (state.border == null) {
                state.border = c.getBorder();
            }
            double sc = Math.min(1, bh / designHeight);
            Insets oi = state.border.getBorderInsets(c);
            Insets si = new Insets(Math.max(1, (int) Math.round(oi.top * sc)), Math.max(1, (int) Math.round(oi.left * sc)),
                    Math.max(1, (int) Math.round(oi.bottom * sc)), Math.max(1, (int) Math.round(oi.right * sc)));
            if (!(c.getBorder() instanceof ScaledBorder && ((ScaledBorder) c.getBorder()).insets.equals(si))) {
                c.setBorder(new ScaledBorder(state.border, si));
            }
        }
        if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            if (state.margin == null) {
                state.margin = b.getMargin();
            }
            if (state.margin != null) {
                double sc = Math.min(1, bh / designHeight);
                Insets m = state.margin;
                Insets scaled = new Insets((int) Math.round(m.top * sc), (int) Math.round(m.left * sc),
                        (int) Math.round(m.bottom * sc), (int) Math.round(m.right * sc));
                if (!scaled.equals(b.getMargin())) {
                    b.setMargin(scaled);
                }
            }
        }
        // Vision's size before rounding: the smaller of the width and height ratios (FPMILayout.scaleFont).
        float size = (float) Math.max(MIN_FONT, Math.min(state.ratioX * bw, state.ratioY * bh));
        Insets in = c.getInsets();
        // A button's text is centered and may run over its padding (Vision's own size does), so only keep it
        // inside the button itself; fields and labels keep their text inside their insets.
        int room = c instanceof AbstractButton ? h - 2 : h - in.top - in.bottom;
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
        Debug.log("fit %s '%s' %dx%d insets %s room %d widthRoom %d size %.2f", c.getClass().getSimpleName(), text, w, h, in, room, widthRoom, size);
        tooltip(c, state, size < TINY && Settings.on(Settings.Fix.HOVER) ? text : null);
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
        if (state.margin != null && c instanceof AbstractButton) {
            ((AbstractButton) c).setMargin(state.margin);
            state.margin = null;
        }
        tooltip(c, state, null);
        // Vision's own font for this size (what FPMILayout.scaleFont computes from the restored ratios): set it here,
        // since a layout pass that doesn't change the component's bounds may not reapply it (popups grown back).
        Rectangle2D b = FPMILayout.getBounds(c);
        if (b != null && b.getWidth() > 0 && b.getHeight() > 0 && c.getFont() != null) {
            int size = Math.max(2, Math.min(Math.round(state.ratioX * (float) b.getWidth()), Math.round(state.ratioY * (float) b.getHeight())));
            if (Math.abs(c.getFont().getSize2D() - size) > 0.01f) {
                c.setFont(c.getFont().deriveFont((float) size));
            }
        }
        c.revalidate();
    }

    /** Shows the text in a large tooltip (or removes ours); a component's own tooltip is never replaced. */
    private static void tooltip(JComponent c, State state, String text) {
        if (text != null && !text.trim().isEmpty()) {
            if (!state.tip && c.getToolTipText() != null) {
                return;
            }
            String html = "<html><div style='font-size:15px; font-weight:bold; padding:2px'>"
                    + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>";
            if (!html.equals(c.getToolTipText())) {
                c.setToolTipText(html);
            }
            state.tip = true;
        } else if (state.tip) {
            c.setToolTipText(null);
            state.tip = false;
        }
    }

    /** A border that paints like the look and feel's own but reports smaller insets (more room for the text). */
    static final class ScaledBorder implements Border {
        final Border inner;
        final Insets insets;

        ScaledBorder(Border inner, Insets insets) {
            this.inner = inner;
            this.insets = insets;
        }

        @Override
        public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int w, int h) {
            inner.paintBorder(c, g, x, y, w, h);
        }

        @Override
        public Insets getBorderInsets(java.awt.Component c) {
            return (Insets) insets.clone();
        }

        @Override
        public boolean isBorderOpaque() {
            return inner.isBorderOpaque();
        }
    }

    private static boolean fits(JComponent c, Font font, String text, int room, int widthRoom) {
        FontMetrics fm = c.getFontMetrics(font);
        // Swing centers the line (ascent + descent) in the room, and digits and capitals only fill the middle of it:
        // the tops of the digits stay inside while room >= 2 x digit height - ascent + descent, and the baseline
        // while room >= ascent - descent. Requiring the whole line to fit shrank text that Vision draws whole.
        int asc = fm.getAscent();
        int desc = fm.getDescent();
        double digit = font.createGlyphVector(fm.getFontRenderContext(), "0").getVisualBounds().getHeight();
        double need = Math.max(asc - desc, 2 * digit - asc + desc);
        if (c instanceof JTextField) {
            // In a running client a value box doesn't place its line where the centered rule expects (tested at
            // 125 % display scaling: a pixel cut off the tops or bottoms of the digits even with two pixels spare),
            // so there the whole line must fit, with one pixel of slack.
            need = asc + desc;
        }
        if (need > room + 1) {
            return false;
        }
        // One pixel to spare: Swing measures the text again with rounding and ends it in "..." when it doesn't fit.
        return text == null || text.isEmpty() || fm.stringWidth(text) <= widthRoom - 1;
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

    /** Gives a component Vision's own font and border back and forgets it (its fix was turned off). */
    static void release(JComponent c) {
        State state = states.remove(c);
        if (state == null) {
            return;
        }
        c.removePropertyChangeListener("font", WATCHER);
        c.removePropertyChangeListener("text", WATCHER);
        c.removePropertyChangeListener("value", WATCHER);
        FPMI_LC lc = FPMILayout.getLayoutConstraints(c);
        applying = true;
        try {
            if (lc != null) {
                restore(c, lc, state);
            } else if (state.border != null) {
                c.setBorder(state.border);
            }
        } finally {
            applying = false;
        }
    }

    /** A setting changed: apply every fix again, in every client window. */
    static void refresh() {
        if (listener == null) {
            return;
        }
        MinimumSize.scanAll();
        for (Frame f : Frame.getFrames()) {
            ClientScale.walk(f, c -> {
                State st = states.get(c);
                if (st != null) {
                    st.key = null;
                }
                if (c instanceof JTextField || c instanceof JLabel || c instanceof AbstractButton || InnerParts.handles(c)) {
                    queue(c);
                }
            });
        }
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
            MinimumSize.restoreAll();
            ClientScale.restoreAll();
            InnerParts.restoreAll();
        } finally {
            applying = false;
        }
    }
}
