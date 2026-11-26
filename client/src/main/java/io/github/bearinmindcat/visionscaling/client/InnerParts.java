package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.VisionDesktop;
import com.inductiveautomation.factorypmi.application.components.AlarmStatusTable;
import com.inductiveautomation.factorypmi.application.components.PMIEasyChart;
import com.inductiveautomation.factorypmi.application.components.PMITable;
import com.inductiveautomation.factorypmi.application.components.VisionAdvancedTable;
import com.inductiveautomation.factorypmi.application.components.tabstrip.PMITabStrip;
import com.inductiveautomation.factorypmi.application.components.util.FPMILayout;
import com.inductiveautomation.factorypmi.application.components.util.FPMI_LC;
import com.inductiveautomation.ignition.common.BasicDataset;
import com.inductiveautomation.ignition.common.Dataset;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shrinks the parts Vision's layout doesn't: a tab strip's tabs (their fonts live in its tab data, plus text padding
 * and space between tabs) and an alarm status table's rows, header and Acknowledge/Shelve buttons. Each is scaled by
 * how much smaller than its design size the component is drawn, and put back at (nearly) design size.
 */
final class InnerParts {

    private static final Logger LOG = LoggerFactory.getLogger("VisionScalingFix");
    private static final String[] FONT_COLUMNS = {"SELECTED_FONT", "UNSELECTED_FONT"};
    /** A tab without a font of its own uses this (TabAttribute's default, "font(Dialog,PLAIN,12)"). */
    private static final Font DEFAULT_TAB_FONT = new Font("Dialog", Font.PLAIN, 12);

    private static final Map<PMITabStrip, TabState> tabs = new WeakHashMap<>();
    private static final Map<AlarmStatusTable, TableState> tables = new WeakHashMap<>();
    private static final Map<JComponent, GridState> grids = new WeakHashMap<>();
    private static final Map<PMIEasyChart, ChartState> charts = new WeakHashMap<>();

    /** A Table or Power Table: row height and header font (Vision's layout scales neither). */
    private static final class GridState {
        final int rowHeight;
        final Font headerFont;
        double scale = 1;

        GridState(int rowHeight, Font headerFont) {
            this.rowHeight = rowHeight;
            this.headerFont = headerFont;
        }
    }

    /** An Easy Chart: title and axis fonts, and the pen panel's check boxes and labels. */
    private static final class ChartState {
        final Font title;
        final Font axisLabel;
        final Font axisTick;
        final Map<JComponent, Font> inner = new HashMap<>();
        double scale = 1;

        ChartState(PMIEasyChart c) {
            title = c.getTitleFont();
            axisLabel = c.getAxisLabelFont();
            axisTick = c.getAxisTickLabelFont();
        }
    }
    private static boolean busy;

    private static final class TabState {
        final Dataset original;
        final int padding;
        final int space;
        final Font font;          // the strip's own font: tabs without a font of their own use it
        final Float ratioX;       // and Vision's ratios for it, when Vision scales it
        final Float ratioY;
        Dataset applied;          // the tab data as the strip holds it after we set it (Vision copies it)
        double scale = 1;
        int width = -1;           // the strip width the labels were fitted to

        TabState(PMITabStrip t) {
            original = t.getTabData();
            padding = t.getTextPadding();
            space = t.getInterTabSpace();
            font = t.getFont();
            FPMI_LC lc = FitText.constraints(t);
            ratioX = lc == null ? null : lc.getFontRatioX();
            ratioY = lc == null ? null : lc.getFontRatioY();
        }
    }

    private static final class TableState {
        final int rowHeight;
        final Font headerFont;
        final Map<AbstractButton, Font> buttons = new HashMap<>();
        double scale = 1;

        TableState(AlarmStatusTable t) {
            rowHeight = t.getRowHeight();
            headerFont = t.getTableHeaderFont();
        }
    }

    private InnerParts() {
    }

    static boolean handles(JComponent c) {
        return c instanceof PMITabStrip || c instanceof AlarmStatusTable || c instanceof PMITable
                || c instanceof VisionAdvancedTable || c instanceof PMIEasyChart;
    }

    /** How much smaller than its design size the component is drawn (at most 1). */
    static double scaleOf(JComponent c) {
        Rectangle2D design = FPMILayout.getPreferredBounds(c);
        Rectangle2D now = FPMILayout.getBounds(c);
        if (design == null || now == null || design.getWidth() <= 0 || design.getHeight() <= 0) {
            return 1;
        }
        return Math.min(1, Math.min(now.getWidth() / design.getWidth(), now.getHeight() / design.getHeight()));
    }

    static void fit(JComponent c) {
        if (busy) {
            return;
        }
        busy = true;
        try {
            // Like docked windows: only while the client is smaller than full screen, so a maximized client keeps
            // Vision's own look (screens designed larger than the screen are drawn shrunk even then).
            VisionDesktop desktop = (VisionDesktop) SwingUtilities.getAncestorOfClass(VisionDesktop.class, c);
            boolean small = desktop != null && ClientScale.scaleOf(desktop) < ClientScale.FULL;
            double s = small && Settings.on(Settings.Fix.PARTS) ? scaleOf(c) : 1;
            if (s >= FitText.SHRUNK) {
                s = 1;
            }
            if (c instanceof PMITabStrip) {
                tab((PMITabStrip) c, s);
            } else if (c instanceof AlarmStatusTable) {
                table((AlarmStatusTable) c, s);
            } else if (c instanceof PMIEasyChart) {
                chart((PMIEasyChart) c, s);
            } else {
                grid(c, s);
            }
        } catch (Throwable t) {
            LOG.debug("Could not scale {}", c.getName(), t);
        } finally {
            busy = false;
        }
    }

    private static void tab(PMITabStrip t, double s) {
        TabState st = tabs.get(t);
        if (st != null && st.applied != null && t.getTabData() != st.applied) {
            tabs.remove(t);  // The project changed the tabs: start again from those.
            st = null;
        }
        if (s == 1) {
            if (st != null) {
                tabs.remove(t);
                t.setTabData(st.original);
                t.setTextPadding(st.padding);
                t.setInterTabSpace(st.space);
            }
            return;
        }
        if (st == null) {
            st = new TabState(t);
            if (st.original == null) {
                return;
            }
            tabs.put(t, st);
        }
        if (Math.abs(st.scale - s) < 0.01 && st.width == t.getWidth()) {
            return;   // same scale and width: nothing to refit (a narrower strip needs smaller labels)
        }
        st.width = t.getWidth();
        double fontScale = s;
        if (t.getOrientation() == PMITabStrip.ORIENTATION_LEFT || t.getOrientation() == PMITabStrip.ORIENTATION_RIGHT) {
            // A side strip is as wide as its window: shrink the labels further until the widest one fits.
            double room = t.getWidth() - 2 * st.padding * s - 12;
            double widest = widestLabel(t, st, s);
            if (widest > 0 && room > 0 && widest > room) {
                fontScale = s * room / widest;
            }
            Debug.log("tabstrip %dx%d scale %.3f room %.1f widest %.1f fontScale %.3f", t.getWidth(), t.getHeight(), s, room, widest, fontScale);
        }
        BasicDataset scaled = new BasicDataset(st.original);
        for (String name : FONT_COLUMNS) {
            int col = scaled.getColumnNames().indexOf(name);
            if (col < 0) {
                continue;
            }
            for (int row = 0; row < scaled.getRowCount(); row++) {
                Object v = scaled.getValueAt(row, col);
                Font f = v instanceof Font ? (Font) v : DEFAULT_TAB_FONT;
                scaled.setValueAt(row, col, f.deriveFont(Math.max(4f, (float) (f.getSize2D() * fontScale))));
            }
        }
        st.scale = s;
        t.setTabData(scaled);
        st.applied = t.getTabData();
        t.setTextPadding(Math.max(2, (int) Math.round(st.padding * s)));
        t.setInterTabSpace(Math.max(0, (int) Math.round(st.space * s)));
    }

    /** The widest tab label at the scaled fonts (a tab's own font, else the strip's), in pixels. */
    private static double widestLabel(PMITabStrip t, TabState st, double s) {
        Dataset data = st.original;
        int nameCol = data.getColumnNames().indexOf("DISPLAY_NAME");
        if (nameCol < 0) {
            nameCol = data.getColumnNames().indexOf("NAME");
        }
        if (nameCol < 0) {
            return 0;
        }
        double widest = 0;
        for (int row = 0; row < data.getRowCount(); row++) {
            Object label = data.getValueAt(row, nameCol);
            if (label == null) {
                continue;
            }
            for (String fontCol : FONT_COLUMNS) {
                int col = data.getColumnNames().indexOf(fontCol);
                Object f = col < 0 ? null : data.getValueAt(row, col);
                Font base = f instanceof Font ? (Font) f : DEFAULT_TAB_FONT;
                {
                    Font scaledFont = base.deriveFont((float) (base.getSize2D() * s));
                    widest = Math.max(widest, t.getFontMetrics(scaledFont).stringWidth(label.toString()));
                }
            }
        }
        return widest;
    }

    private static void table(AlarmStatusTable t, double s) {
        TableState st = tables.get(t);
        if (s == 1) {
            if (st != null) {
                tables.remove(t);
                t.setRowHeight(st.rowHeight);
                if (st.headerFont != null) {
                    t.setTableHeaderFont(st.headerFont);
                }
                st.buttons.forEach(AbstractButton::setFont);
                t.revalidate();
            }
            return;
        }
        if (st == null) {
            st = new TableState(t);
            tables.put(t, st);
        }
        if (Math.abs(st.scale - s) < 0.01) {
            return;
        }
        st.scale = s;
        t.setRowHeight(Math.max(6, (int) Math.round(st.rowHeight * s)));
        if (st.headerFont != null) {
            t.setTableHeaderFont(st.headerFont.deriveFont(Math.max(4f, (float) (st.headerFont.getSize2D() * s))));
        }
        TableState state = st;
        double factor = s;
        ClientScale.walk(t, c -> {
            if (c instanceof AbstractButton && FitText.constraints(c) == null && c.getFont() != null) {
                Font original = state.buttons.computeIfAbsent((AbstractButton) c, b -> b.getFont());
                c.setFont(original.deriveFont(Math.max(4f, (float) (original.getSize2D() * factor))));
            }
        });
        t.revalidate();
    }

    private static Font scaled(Font f, double s) {
        return f == null ? null : f.deriveFont(Math.max(4f, (float) (f.getSize2D() * s)));
    }

    /** Table (PMITable) or Power Table (VisionAdvancedTable). */
    private static void grid(JComponent t, double s) {
        boolean power = t instanceof VisionAdvancedTable;
        GridState st = grids.get(t);
        if (s == 1) {
            if (st != null) {
                grids.remove(t);
                if (power) {
                    ((VisionAdvancedTable) t).setRowHeight(st.rowHeight);
                    if (st.headerFont != null) {
                        ((VisionAdvancedTable) t).setHeaderFont(st.headerFont);
                    }
                } else {
                    ((PMITable) t).setRowHeight(st.rowHeight);
                    if (st.headerFont != null) {
                        ((PMITable) t).setHeaderFont(st.headerFont);
                    }
                }
                t.revalidate();
            }
            return;
        }
        if (st == null) {
            st = power ? new GridState(((VisionAdvancedTable) t).getRowHeight(), ((VisionAdvancedTable) t).getHeaderFont())
                    : new GridState(((PMITable) t).getRowHeight(), ((PMITable) t).getHeaderFont());
            grids.put(t, st);
        }
        if (Math.abs(st.scale - s) < 0.01) {
            return;
        }
        st.scale = s;
        int rows = Math.max(6, (int) Math.round(st.rowHeight * s));
        if (power) {
            ((VisionAdvancedTable) t).setRowHeight(rows);
            if (st.headerFont != null) {
                ((VisionAdvancedTable) t).setHeaderFont(scaled(st.headerFont, s));
            }
        } else {
            ((PMITable) t).setRowHeight(rows);
            if (st.headerFont != null) {
                ((PMITable) t).setHeaderFont(scaled(st.headerFont, s));
            }
        }
        t.revalidate();
    }

    private static void chart(PMIEasyChart c, double s) {
        ChartState st = charts.get(c);
        if (s == 1) {
            if (st != null) {
                charts.remove(c);
                if (st.title != null) {
                    c.setTitleFont(st.title);
                }
                if (st.axisLabel != null) {
                    c.setAxisLabelFont(st.axisLabel);
                }
                if (st.axisTick != null) {
                    c.setAxisTickLabelFont(st.axisTick);
                }
                st.inner.forEach(JComponent::setFont);
                c.revalidate();
            }
            return;
        }
        if (st == null) {
            st = new ChartState(c);
            charts.put(c, st);
        }
        if (Math.abs(st.scale - s) < 0.01) {
            return;
        }
        st.scale = s;
        if (st.title != null) {
            c.setTitleFont(scaled(st.title, s));
        }
        if (st.axisLabel != null) {
            c.setAxisLabelFont(scaled(st.axisLabel, s));
        }
        if (st.axisTick != null) {
            c.setAxisTickLabelFont(scaled(st.axisTick, s));
        }
        // The pen panel beside the plot: check boxes and labels that Vision doesn't lay out.
        ChartState state = st;
        ClientScale.walk(c, part -> {
            if ((part instanceof AbstractButton || part instanceof javax.swing.JLabel) && FitText.constraints(part) == null
                    && part.getFont() != null) {
                Font original = state.inner.computeIfAbsent(part, p -> p.getFont());
                part.setFont(scaled(original, s));
            }
        });
        c.revalidate();
    }

    static void restoreAll() {
        for (PMITabStrip t : new ArrayList<>(tabs.keySet())) {
            tab(t, 1);
        }
        for (AlarmStatusTable t : new ArrayList<>(tables.keySet())) {
            table(t, 1);
        }
        for (JComponent t : new ArrayList<>(grids.keySet())) {
            grid(t, 1);
        }
        for (PMIEasyChart c : new ArrayList<>(charts.keySet())) {
            chart(c, 1);
        }
    }
}
