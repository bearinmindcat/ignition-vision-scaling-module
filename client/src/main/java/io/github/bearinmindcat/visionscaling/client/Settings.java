package io.github.bearinmindcat.visionscaling.client;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which fixes are on, per Windows user (Java preferences). A Java system property on the client
 * ({@code -Dvisionscaling.<key>=...}, e.g. through {@code JAVA_TOOL_OPTIONS}) overrides the saved choice and locks it
 * in the settings window, for a PC an administrator sets up.
 */
final class Settings {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");

    enum Fix {
        ENABLED("enabled", "Turn on Vision Scaling Fix", ""),
        FIELDS("fields", "Value boxes", "numbers fit inside the box"),
        LABELS("labels", "Labels", "text fits, or ends in \"...\" with the rest on hover"),
        BUTTONS("buttons", "Buttons", "text fits on the button"),
        PARTS("parts", "Tables, charts and tabs", "rows, headers, axes and menu tabs shrink too"),
        HOVER("hover", "Read tiny text", "point at it to see it at normal size"),
        DOCKS("docks", "Title bar and menus", "shrink with the window"),
        ANCHORED("anchored", "Header bars and bottom tabs", "shrink with the screen"),
        POPUPS("popups", "Popups", "shrink to fit the window"),
        NO_SCROLLBARS("noScrollBars", "", "");   // shown as the "Smallest size" choice

        final String key;
        final String label;
        final String hint;

        Fix(String key, String label, String hint) {
            this.key = key;
            this.label = label;
            this.hint = hint;
        }
    }

    /** How small the client may get before it scrolls. */
    enum Limit {
        ANY("Any size", "100x100"),
        HALF("Half the screen", "half"),
        PROJECT("The project's minimum (scroll bars)", null);

        final String label;
        final String minSize;

        Limit(String label, String minSize) {
            this.label = label;
            this.minSize = minSize;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Settings() {
    }

    private static Preferences prefs() {
        return Preferences.userRoot().node("io/github/bearinmindcat/visionscaling");
    }

    /** The system property that overrides a fix on this PC, or null. */
    static String override(Fix fix) {
        String v = System.getProperty("visionscaling." + fix.key);
        if (fix == Fix.NO_SCROLLBARS && v == null && "off".equalsIgnoreCase(System.getProperty("visionscaling.minSize", "").trim())) {
            return "false";  // -Dvisionscaling.minSize=off keeps the project's minimum (and its scroll bars)
        }
        return v;
    }

    static boolean locked(Fix fix) {
        return override(fix) != null;
    }

    /** Whether a fix is on (every fix is off while "Turn on Vision Scaling Fix" is off). */
    static boolean on(Fix fix) {
        if (fix != Fix.ENABLED && !saved(Fix.ENABLED)) {
            return false;
        }
        return saved(fix);
    }

    /** The check box's own state, whatever the master switch says. */
    static boolean saved(Fix fix) {
        String v = override(fix);
        if (v != null) {
            return Boolean.parseBoolean(v.trim());
        }
        try {
            return prefs().getBoolean(fix.key, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    static void set(Fix fix, boolean on) {
        try {
            prefs().putBoolean(fix.key, on);
        } catch (RuntimeException e) {
            LOG.warn("Could not save the setting {}", fix.key, e);
        }
        changed();
    }

    static void resetToDefaults() {
        try {
            for (Fix f : Fix.values()) {
                prefs().remove(f.key);
            }
            prefs().remove("minSize");
        } catch (RuntimeException e) {
            LOG.warn("Could not reset the settings", e);
        }
        changed();
    }

    // Smallest size ------------------------------------------------------------------------------------------------

    static boolean limitLocked() {
        return locked(Fix.NO_SCROLLBARS) || System.getProperty("visionscaling.minSize") != null;
    }

    static Limit limit() {
        if (!saved(Fix.NO_SCROLLBARS)) {
            return Limit.PROJECT;
        }
        return "half".equalsIgnoreCase(minSizeText()) ? Limit.HALF : Limit.ANY;
    }

    static void setLimit(Limit limit) {
        try {
            prefs().putBoolean(Fix.NO_SCROLLBARS.key, limit != Limit.PROJECT);
            if (limit.minSize != null) {
                prefs().put("minSize", limit.minSize);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not save the smallest size", e);
        }
        changed();
    }

    static String minSizeText() {
        String v = System.getProperty("visionscaling.minSize");
        if (v != null && !v.trim().equalsIgnoreCase("off")) {
            return v.trim();
        }
        try {
            return prefs().get("minSize", Limit.ANY.minSize);
        } catch (RuntimeException e) {
            return Limit.ANY.minSize;
        }
    }

    /** The client minimum size on a screen when scroll bars are off: a size, or half the screen's work area. */
    static Dimension minSize(GraphicsConfiguration gc) {
        String text = minSizeText();
        if ("half".equalsIgnoreCase(text)) {
            Rectangle screen = gc != null ? gc.getBounds()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            Insets si = gc != null ? Toolkit.getDefaultToolkit().getScreenInsets(gc) : new Insets(0, 0, 0, 0);
            return new Dimension((screen.width - si.left - si.right) / 2, (screen.height - si.top - si.bottom) / 2);
        }
        Dimension d = parse(text);
        return d != null ? d : new Dimension(100, 100);
    }

    static Dimension parse(String text) {
        try {
            String[] parts = text.trim().toLowerCase().split("x");
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            return w > 0 && h > 0 ? new Dimension(w, h) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.debug("Settings listener failed", e);
            }
        }
    }
}
