package io.github.bearinmindcat.visionscaling.client;

import com.inductiveautomation.ignition.client.model.ClientContext;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.vision.api.client.AbstractClientModuleHook;

/** Vision client entry point. */
public class ClientHook extends AbstractClientModuleHook {

    @Override
    public void startup(ClientContext context, LicenseState activationState) throws Exception {
        FitText.install();
    }

    @Override
    public void shutdown() {
        FitText.uninstall();
    }
}
