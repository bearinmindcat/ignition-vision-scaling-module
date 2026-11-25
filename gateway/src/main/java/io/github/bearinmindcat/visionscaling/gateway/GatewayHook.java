package io.github.bearinmindcat.visionscaling.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * The module does all its work in Vision clients. This hook exists so the Gateway treats it as a free module
 * (no license, no trial timer).
 */
public class GatewayHook extends AbstractGatewayModuleHook {

    @Override
    public void setup(GatewayContext context) {
    }

    @Override
    public void startup(LicenseState activationState) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
