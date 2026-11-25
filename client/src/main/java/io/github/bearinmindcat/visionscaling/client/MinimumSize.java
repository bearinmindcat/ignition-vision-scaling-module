package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.VisionDesktop;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets the client keep shrinking its windows instead of adding scroll bars.
 *
 * <p>Vision doesn't lay its screens out smaller than the project's Minimum Size (Project Properties → Vision →
 * User Interface, 800x600 by default): below that the client scrolls and part of the screen is out of view. This
 * lowers the limit in each running client (to 100x100, or {@code -Dvisionscaling.minSize=WxH}; {@code off} keeps
 * the project's value). The project isn't changed; uninstalling the module puts the project's value back.
 */
final class MinimumSize {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");
    private static final Map<VisionDesktop, Dimension> original = new WeakHashMap<>();

    private MinimumSize() {
    }

    /** The minimum to use, or null to keep the project's. */
    static Dimension wanted() {
        String value = System.getProperty("visionscaling.minSize", "100x100").trim().toLowerCase();
        if (value.equals("off")) {
            return null;
        }
        try {
            String[] parts = value.split("x");
            return new Dimension(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (RuntimeException e) {
            LOG.warn("visionscaling.minSize should look like 300x200 or off, not '{}'; using 100x100.", value);
            return new Dimension(100, 100);
        }
    }

    /** Looks for client desktops inside a window (on the event thread). */
    static void scan(Component c) {
        if (c instanceof VisionDesktop) {
            apply((VisionDesktop) c);
        } else if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                scan(child);
            }
        }
    }

    static void scanAll() {
        for (Frame f : Frame.getFrames()) {
            scan(f);
        }
    }

    static void apply(VisionDesktop desktop) {
        Dimension want = wanted();
        if (want == null) {
            return;
        }
        Dimension current = desktop.getMinSize();
        if (want.equals(current)) {
            return;
        }
        // Vision's own value (from the project; set again when the project is updated).
        original.put(desktop, current == null ? null : new Dimension(current));
        desktop.setMinSize(new Dimension(want));
        LOG.debug("Client minimum size {} lowered to {}", current, want);
    }

    static void restoreAll() {
        for (Map.Entry<VisionDesktop, Dimension> e : new ArrayList<>(original.entrySet())) {
            e.getKey().setMinSize(e.getValue());
        }
        original.clear();
    }
}
