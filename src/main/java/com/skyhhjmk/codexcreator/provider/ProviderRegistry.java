package com.skyhhjmk.codexcreator.provider;

import com.skyhhjmk.codexcreator.domain.ModelProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderRegistry {
    @Inject
    Instance<ProviderAdapter> adapters;

    public ProviderAdapter resolve(ModelProfile profile) {
        String providerType = profile.providerConfig == null
                ? "CODEX_APP_SERVER" : profile.providerConfig.providerType;
        for (ProviderAdapter adapter : adapters) {
            if (adapter.supports(providerType)) return adapter;
        }
        throw new IllegalArgumentException("No adapter configured for provider " + providerType);
    }
}
