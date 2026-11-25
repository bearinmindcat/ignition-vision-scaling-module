package io.github.bearinmindcat.visionscaling.client;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** The settings window: one check box per fix, applied at once. Ctrl+Period or system.visionscaling.openSettings(). */
final class SettingsDialog {

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
        JDialog d = new JDialog(owner, "Vision Scaling Fix Settings", Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Map<Settings.Fix, JCheckBox> boxes = new EnumMap<>(Settings.Fix.class);
        JPanel text = group("Text in small windows");
        JPanel layout = group("Layout in small windows");
        for (Settings.Fix fix : Settings.Fix.values()) {
            JCheckBox box = new JCheckBox(fix.label, Settings.on(fix));
            if (Settings.locked(fix)) {
                box.setEnabled(false);
                box.setToolTipText("Set on this PC by -Dvisionscaling." + fix.key + "=" + Settings.override(fix));
            }
            box.addActionListener(e -> Settings.set(fix, box.isSelected()));
            boxes.put(fix, box);
            boolean textGroup = fix == Settings.Fix.FIELDS || fix == Settings.Fix.LABELS
                    || fix == Settings.Fix.BUTTONS || fix == Settings.Fix.PARTS;
            (textGroup ? text : layout).add(box);
            if (fix == Settings.Fix.NO_SCROLLBARS) {
                layout.add(minSizeRow());
            }
        }

        JLabel note = new JLabel("<html>Saved for the current Windows user. Ctrl+Period opens this window.<br>"
                + "Text fixes apply to anything drawn smaller than designed; layout fixes only while the<br>"
                + "client is smaller than full screen. A component at its design size keeps Vision's own look.</html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> {
            Settings.resetToDefaults();
            boxes.forEach((fix, box) -> box.setSelected(Settings.on(fix)));
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reset);
        buttons.add(close);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        body.add(text);
        body.add(Box.createVerticalStrut(8));
        body.add(layout);
        body.add(note);
        d.getContentPane().add(body, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(close);
        d.pack();
        d.setLocationRelativeTo(owner);
        dialog = d;
        d.setVisible(true);
    }

    private static JPanel group(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel minSizeRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.add(Box.createHorizontalStrut(20));
        row.add(new JLabel("Smallest client size (width x height):"));
        JTextField field = new JTextField(Settings.minSizeText(), 8);
        if (Settings.minSizeLocked()) {
            field.setEnabled(false);
            field.setToolTipText("Set on this PC by -Dvisionscaling.minSize");
        }
        Runnable apply = () -> {
            if (Settings.parse(field.getText()) == null) {
                Toolkit.getDefaultToolkit().beep();
                field.setText(Settings.minSizeText());
            } else if (!field.getText().trim().equalsIgnoreCase(Settings.minSizeText())) {
                Settings.setMinSizeText(field.getText());
            }
        };
        field.addActionListener(e -> apply.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                apply.run();
            }
        });
        row.add(field);
        return row;
    }
}
