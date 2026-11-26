package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.factorypmi.application.VisionDesktop;
import com.inductiveautomation.factorypmi.application.components.AlarmStatusTable;
import com.inductiveautomation.factorypmi.application.components.PMIDateRange;
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

    /**
     * An Easy Chart: the fonts of its title and axes (the live JFreeChart objects, keyed by object, so axes that
     * Vision builds anew are picked up), and the pen panel's check boxes and labels.
     */
    private static final class ChartState {
        final Map<Object, Font[]> parts = new WeakHashMap<>();
        double scale = 1;
        // The date range strip under the plot (zoom bar, slider, Apply): its height comes from its contents, so at a
        // small size it kept its full height and squeezed the plot out. It shrinks with the chart.
        PMIDateRange range;
        java.awt.Dimension rangePref;
        boolean rangePrefSet;
        java.awt.Dimension rangeMin;
        boolean rangeMinSet;
        // Its slider (the long track under the zoom bar) keeps a fixed preferred height: scaled with the chart.
        JComponent slider;
        java.awt.Dimension sliderPref;
        boolean sliderPrefSet;
        java.awt.Dimension sliderMin;
        boolean sliderMinSet;
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
                Debug.log("chart restore: %d chart parts", st.parts.size());
                st.parts.forEach(InnerParts::setFonts);
                if (st.range != null) {
                    st.range.setPreferredSize(st.rangePrefSet ? st.rangePref : null);
                    st.range.setMinimumSize(st.rangeMinSet ? st.rangeMin : null);
                }
                if (st.slider != null) {
                    st.slider.setPreferredSize(st.sliderPrefSet ? st.sliderPref : null);
                    st.slider.setMinimumSize(st.sliderMinSet ? st.sliderMin : null);
                    st.slider.revalidate();
                }
                if (st.range != null) {
                    st.range.revalidate();
                }
                c.revalidate();
            }
            return;
        }
        boolean first = st == null;
        if (first) {
            st = new ChartState();
            charts.put(c, st);
        }
        // The Easy Chart's own font setters (setAxisLabelFont, ...) call updateConfig(), which rebuilds the chart from
        // its pen datasets and drops the pens the operator ticked. The live title and axes take a font without that.
        boolean[] fresh = {false};
        for (Object part : chartParts(c)) {
            if (!st.parts.containsKey(part)) {
                // Seen before anything was shrunk: its own fonts. An axis or title Vision builds later, while the
                // chart is small, copies the shrunk fonts, so it gets the chart's configured fonts instead.
                Font[] fonts = first ? fonts(part) : configuredFonts(c, part);
                if (fonts != null) {
                    st.parts.put(part, fonts);
                    fresh[0] = true;
                }
            }
        }
        // Vision scales the chart's own font with the window, and PMIEasyChart.setFont passes it on to the pen list and
        // the date range, so their text needs nothing here. Their sizes don't follow: the date range keeps its height.
        ChartState state = st;
        ClientScale.walk(c, part -> {
            if (part instanceof PMIDateRange) {
                if (state.range != part) {
                    state.range = (PMIDateRange) part;
                    state.rangePrefSet = part.isPreferredSizeSet();
                    state.rangePref = part.getPreferredSize();
                    state.rangeMinSet = part.isMinimumSizeSet();
                    state.rangeMin = part.getMinimumSize();
                    fresh[0] = true;
                }
            } else if (part.getClass().getName().endsWith("$DateRangeSlider")) {
                if (state.slider != part) {
                    state.slider = part;
                    state.sliderPrefSet = part.isPreferredSizeSet();
                    state.sliderPref = part.getPreferredSize();
                    state.sliderMinSet = part.isMinimumSizeSet();
                    state.sliderMin = part.getMinimumSize();
                    fresh[0] = true;
                }
            }
        });
        if (!fresh[0] && Math.abs(st.scale - s) < 0.01) {
            return;
        }
        Debug.log("chart scale %.2f: %d chart parts", s, st.parts.size());
        st.scale = s;
        for (Map.Entry<Object, Font[]> e : st.parts.entrySet()) {
            Font[] original = e.getValue();
            Font[] small = new Font[original.length];
            for (int i = 0; i < original.length; i++) {
                small[i] = scaled(original[i], s);
            }
            setFonts(e.getKey(), small);
        }
        if (st.range != null) {
            // Its own layout gives the zoom bar (icons) its full height first; the panel is held to its scaled height
            // so the plot keeps its share (the slider under the zoom bar gets what is left, often nothing).
            int h = Math.max(24, (int) Math.round(st.rangePref.height * s));
            // Minimum too: when the chart is short of room its layout sizes the parts by their minimum.
            st.range.setPreferredSize(new java.awt.Dimension(st.rangePref.width, h));
            st.range.setMinimumSize(new java.awt.Dimension(Math.min(st.rangeMin.width, st.rangePref.width), Math.min(h, st.rangeMin.height)));
        }
        if (st.slider != null) {
            int h = Math.max(10, (int) Math.round(st.sliderPref.height * s));
            st.slider.setPreferredSize(new java.awt.Dimension(st.sliderPref.width, h));
            st.slider.setMinimumSize(new java.awt.Dimension(st.sliderMin.width, Math.min(h, st.sliderMin.height)));
            st.slider.revalidate();
        }
        if (st.range != null) {
            // setPreferredSize doesn't invalidate: the panels around the date range kept their cached size.
            st.range.revalidate();
        }
        c.revalidate();
    }

    /** The chart's configured title or axis fonts (Vision's properties), else the part's own. */
    private static Font[] configuredFonts(PMIEasyChart c, Object part) {
        Font[] own = fonts(part);
        if (own == null) {
            return null;
        }
        if (own.length == 2) {
            return new Font[] {c.getAxisLabelFont() != null ? c.getAxisLabelFont() : own[0],
                    c.getAxisTickLabelFont() != null ? c.getAxisTickLabelFont() : own[1]};
        }
        return new Font[] {c.getTitleFont() != null ? c.getTitleFont() : own[0]};
    }

    /** The chart's title and axes (JFreeChart objects, reached by reflection: Vision's copy of JFreeChart). */
    private static java.util.List<Object> chartParts(PMIEasyChart c) {
        java.util.List<Object> out = new ArrayList<>();
        try {
            if (CHART_FIELD == null) {
                return out;
            }
            Object chart = CHART_FIELD.get(c);
            if (chart == null) {
                return out;
            }
            // PMIEasyChart.chart is a ChartPanel (Swing); the JFreeChart with the title and axes is behind getChart().
            Object inside = call(chart, "getChart");
            if (inside != null) {
                chart = inside;
            }
            Object title = call(chart, "getTitle");
            if (title != null) {
                out.add(title);
            }
            Object plot = call(chart, "getPlot");
            java.util.List<Object> plots = new ArrayList<>();
            if (plot != null) {
                plots.add(plot);
                Object subs = call(plot, "getSubplots");
                if (subs instanceof java.util.List) {
                    plots.addAll((java.util.List<?>) subs);
                }
            }
            for (Object pl : plots) {
                for (String kind : new String[] {"Domain", "Range"}) {
                    Object count = call(pl, "get" + kind + "AxisCount");
                    int n = count instanceof Integer ? (Integer) count : 0;
                    for (int i = 0; i < n; i++) {
                        Object axis = pl.getClass().getMethod("get" + kind + "Axis", int.class).invoke(pl, i);
                        if (axis != null && !out.contains(axis)) {
                            out.add(axis);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Easy Chart parts not readable", e);
        }
        return out;
    }

    private static final java.lang.reflect.Field CHART_FIELD = chartField();

    private static java.lang.reflect.Field chartField() {
        try {
            java.lang.reflect.Field f = PMIEasyChart.class.getDeclaredField("chart");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("PMIEasyChart.chart not found", e);
            return null;
        }
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** A title's font, or an axis's label and tick label fonts. */
    private static Font[] fonts(Object part) {
        try {
            if (hasMethod(part, "getLabelFont")) {
                return new Font[] {(Font) call(part, "getLabelFont"), (Font) call(part, "getTickLabelFont")};
            }
            if (hasMethod(part, "getFont")) {
                return new Font[] {(Font) call(part, "getFont")};
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Font not readable on {}", part, e);
        }
        return null;
    }

    private static void setFonts(Object part, Font[] fonts) {
        try {
            if (fonts.length == 2) {
                if (fonts[0] != null) {
                    part.getClass().getMethod("setLabelFont", Font.class).invoke(part, fonts[0]);
                }
                if (fonts[1] != null) {
                    part.getClass().getMethod("setTickLabelFont", Font.class).invoke(part, fonts[1]);
                }
            } else if (fonts[0] != null) {
                part.getClass().getMethod("setFont", Font.class).invoke(part, fonts[0]);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Font not settable on {}", part, e);
        }
    }

    private static boolean hasMethod(Object o, String name) {
        try {
            o.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
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
