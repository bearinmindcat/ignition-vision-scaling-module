package io.github.bearinmindcat.visionscaling.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

/** The settings window: one plain check box per fix, applied at once. Ctrl+Period or system.visionscaling.openSettings(). */
final class SettingsDialog {

    private static final Color HINT = new Color(0x6B, 0x72, 0x7A);
    private static JDialog dialog;

    private SettingsDialog() {
    }

    static void open() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(SettingsDialog::open);
            return;
        }
        if (dialog != null && dialog.isDisplayable()) {
            dialog.toFront();
            return;
        }
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog d = new JDialog(owner, "Vision Scaling Fix", Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        Rows rows = new Rows(body);

        Map<Settings.Fix, JCheckBox> boxes = new EnumMap<>(Settings.Fix.class);
        List<JComponent> dependents = new ArrayList<>();

        JCheckBox master = box(Settings.Fix.ENABLED, boxes);
        master.setFont(master.getFont().deriveFont(Font.BOLD));
        rows.full(master);

        rows.heading("Text");
        for (Settings.Fix fix : new Settings.Fix[] {Settings.Fix.FIELDS, Settings.Fix.LABELS, Settings.Fix.BUTTONS,
                Settings.Fix.PARTS, Settings.Fix.HOVER}) {
            JCheckBox b = box(fix, boxes);
            dependents.add(b);
            rows.pair(b, hint(fix.hint));
        }

        rows.heading("Small windows");
        for (Settings.Fix fix : new Settings.Fix[] {Settings.Fix.DOCKS, Settings.Fix.ANCHORED, Settings.Fix.POPUPS}) {
            JCheckBox b = box(fix, boxes);
            dependents.add(b);
            rows.pair(b, hint(fix.hint));
        }

        JComboBox<Settings.Limit> limit = new JComboBox<>(Settings.Limit.values());
        limit.setSelectedItem(Settings.limit());
        limit.addActionListener(e -> Settings.setLimit((Settings.Limit) limit.getSelectedItem()));
        dependents.add(limit);
        rows.pair(new JLabel("Smallest size"), limit);


        Runnable greyOut = () -> {
            boolean on = master.isSelected();
            for (JComponent c : dependents) {
                boolean locked = c == limit ? Settings.limitLocked()
                        : c instanceof JCheckBox && Settings.locked(fixOf((JCheckBox) c, boxes));
                c.setEnabled(on && !locked);
            }
        };
        master.addActionListener(e -> greyOut.run());
        greyOut.run();

        JLabel note = hint("Saved for you on this PC. Ctrl+Period opens this window.");
        note.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        rows.full(note);

        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            Settings.resetToDefaults();
            boxes.forEach((fix, b) -> b.setSelected(Settings.saved(fix)));
            limit.setSelectedItem(Settings.limit());
            greyOut.run();
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(reset);
        buttons.add(close);

        d.getContentPane().add(body, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(close);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(owner);
        dialog = d;
        d.setVisible(true);
    }

    private static JCheckBox box(Settings.Fix fix, Map<Settings.Fix, JCheckBox> boxes) {
        JCheckBox b = new JCheckBox(fix.label, Settings.saved(fix));
        if (Settings.locked(fix)) {
            b.setEnabled(false);
            b.setToolTipText("Set on this PC by -Dvisionscaling." + fix.key + "=" + Settings.override(fix));
        }
        b.addActionListener(e -> Settings.set(fix, b.isSelected()));
        boxes.put(fix, b);
        return b;
    }

    private static Settings.Fix fixOf(JCheckBox b, Map<Settings.Fix, JCheckBox> boxes) {
        for (Map.Entry<Settings.Fix, JCheckBox> e : boxes.entrySet()) {
            if (e.getValue() == b) {
                return e.getKey();
            }
        }
        return Settings.Fix.ENABLED;
    }

    private static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(HINT);
        return l;
    }

    /** Two-column rows: a control and its hint. */
    private static final class Rows {
        private final JPanel panel;
        private int row;

        Rows(JPanel panel) {
            this.panel = panel;
        }

        void full(JComponent c) {
            GridBagConstraints g = base();
            g.gridwidth = 2;
            panel.add(c, g);
        }

        void heading(String text) {
            GridBagConstraints g = base();
            g.gridwidth = 2;
            g.insets = new Insets(12, 0, 4, 0);
            g.fill = GridBagConstraints.HORIZONTAL;
            panel.add(new JSeparator(), g);
            JLabel l = new JLabel(text);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            full(l);
        }

        void pair(JComponent left, JComponent right) {
            int r = row++;
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = r;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, 0, 2, 18);
            panel.add(left, g);
            g = new GridBagConstraints();
            g.gridx = 1;
            g.gridy = r;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, 0, 2, 0);
            panel.add(right, g);
        }

        private GridBagConstraints base() {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = row++;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, 0, 2, 0);
            return g;
        }
    }
}
