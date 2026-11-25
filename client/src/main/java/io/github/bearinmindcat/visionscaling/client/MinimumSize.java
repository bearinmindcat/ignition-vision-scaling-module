package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.VisionDesktop;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets the client keep shrinking its windows instead of adding scroll bars ("No scroll bars").
 *
 * <p>Vision doesn't lay its screens out smaller than the project's Minimum Size (Project Properties → Vision →
 * User Interface, 800x600 by default): below that the client scrolls and part of the screen is out of view. This
 * lowers the limit in each running client (100x100 by default). The project isn't changed; turning the fix off or
 * uninstalling the module puts the project's value back. Also hands each client desktop to {@link ClientScale}.
 */
final class MinimumSize {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");
    /** Vision's own minimum (from the project), per desktop, while ours replaces it. */
    private static final Map<VisionDesktop, Dimension> original = new WeakHashMap<>();
    /** The minimum this module set, per desktop. */
    private static final Map<VisionDesktop, Dimension> applied = new WeakHashMap<>();

    private MinimumSize() {
    }

    /** Looks for client desktops inside a window (on the event thread). */
    static void scan(Component c) {
        if (c instanceof VisionDesktop) {
            VisionDesktop desktop = (VisionDesktop) c;
            apply(desktop);
            // After Vision's own layout for the new size.
            ClientScale.schedule(desktop);
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
        Dimension current = desktop.getMinSize();
        Dimension ours = applied.get(desktop);
        if (ours != null && !ours.equals(current)) {
            // Vision set its own value again (e.g. the project was updated).
            original.remove(desktop);
            applied.remove(desktop);
            ours = null;
        }
        if (!Settings.on(Settings.Fix.NO_SCROLLBARS)) {
            if (ours != null) {
                Dimension back = original.remove(desktop);
                applied.remove(desktop);
                desktop.setMinSize(back);
            }
            return;
        }
        Dimension want = Settings.minSize(desktop.getGraphicsConfiguration());
        if (want.equals(current)) {
            return;
        }
        if (ours == null) {
            original.put(desktop, current == null ? null : new Dimension(current));
        }
        applied.put(desktop, new Dimension(want));
        desktop.setMinSize(new Dimension(want));
        LOG.debug("Client minimum size {} set to {}", current, want);
    }

    static void restoreAll() {
        for (Map.Entry<VisionDesktop, Dimension> e : new ArrayList<>(original.entrySet())) {
            if (Objects.equals(applied.get(e.getKey()), e.getKey().getMinSize())) {
                e.getKey().setMinSize(e.getValue());
            }
        }
        original.clear();
        applied.clear();
    }
}
