package com.skyhhjmk.codexcreator.service;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "codex.creator.task")
public interface RuntimeInferenceConfig {
    @WithDefault("3")
    int maxAttempts();
}
