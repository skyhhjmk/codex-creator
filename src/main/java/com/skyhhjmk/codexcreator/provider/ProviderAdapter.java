package com.skyhhjmk.codexcreator.provider;

import java.util.concurrent.CompletableFuture;

public interface ProviderAdapter {
    boolean supports(String providerType);

    CompletableFuture<ProviderResponse> infer(ProviderRequest request);
}
