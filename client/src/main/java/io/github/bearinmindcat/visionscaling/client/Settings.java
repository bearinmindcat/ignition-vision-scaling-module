package io.github.bearinmindcat.visionscaling.client;

import java.awt.Dimension;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which fixes are on, per Windows user (Java preferences). A Java system property on the client
 * ({@code -Dvisionscaling.<key>=true|false}, e.g. through {@code JAVA_TOOL_OPTIONS}) overrides the saved choice and
 * locks it in the settings window, for a PC an administrator sets up.
 */
final class Settings {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");
    static final String DEFAULT_MIN_SIZE = "100x100";

    enum Fix {
        FIELDS("fields", "Value boxes (thin border and text that fits)", true),
        LABELS("labels", "Labels (text that fits instead of \"...\")", true),
        BUTTONS("buttons", "Buttons (text that fits)", true),
        PARTS("parts", "Tables and tab strips (rows, headers and tabs shrink with them)", true),
        NO_SCROLLBARS("noScrollBars", "No scroll bars (keep shrinking below the project's minimum size)", true),
        DOCKS("docks", "Docked windows (title bars and menus shrink with the window)", true),
        ANCHORED("anchored", "Anchored parts (header bars and tab strips shrink with the screen)", true);

        final String key;
        final String label;
        final boolean shownByDefault;

        Fix(String key, String label, boolean on) {
            this.key = key;
            this.label = label;
            this.shownByDefault = on;
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

    static boolean on(Fix fix) {
        String v = override(fix);
        if (v != null) {
            return Boolean.parseBoolean(v.trim());
        }
        try {
            return prefs().getBoolean(fix.key, fix.shownByDefault);
        } catch (RuntimeException e) {
            return fix.shownByDefault;
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

    static boolean minSizeLocked() {
        String v = System.getProperty("visionscaling.minSize");
        return v != null && !v.trim().equalsIgnoreCase("off");
    }

    static String minSizeText() {
        if (minSizeLocked()) {
            return System.getProperty("visionscaling.minSize").trim();
        }
        try {
            return prefs().get("minSize", DEFAULT_MIN_SIZE);
        } catch (RuntimeException e) {
            return DEFAULT_MIN_SIZE;
        }
    }

    static void setMinSizeText(String text) {
        if (parse(text) == null) {
            return;
        }
        try {
            prefs().put("minSize", text.trim().toLowerCase());
        } catch (RuntimeException e) {
            LOG.warn("Could not save the minimum size", e);
        }
        changed();
    }

    /** The client minimum size to use when "No scroll bars" is on. */
    static Dimension minSize() {
        Dimension d = parse(minSizeText());
        return d != null ? d : parse(DEFAULT_MIN_SIZE);
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
