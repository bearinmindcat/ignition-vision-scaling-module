package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.ignition.common.script.hints.ScriptFunction;

/**
 * The {@code system.visionscaling} scripting functions, available in Vision clients. For example, a client menu
 * item (Project Properties, Vision, Menu Bar) can call {@code system.visionscaling.openSettings()}.
 */
public final class ScalingScripts {

    private static final String BUNDLE = "ScalingScripts";

    private ScalingScripts() {
    }

    public static void register(ScriptManager manager) {
        BundleUtil.get().addBundle(
            BUNDLE, ScalingScripts.class.getClassLoader(), ScalingScripts.class.getName().replace('.', '/'));
        manager.addScriptModule("system.visionscaling", ScalingScripts.class, new PropertiesFileDocProvider());
    }

    @ScriptFunction(docBundlePrefix = BUNDLE)
    public static void openSettings() {
        SettingsDialog.open();
    }
}
