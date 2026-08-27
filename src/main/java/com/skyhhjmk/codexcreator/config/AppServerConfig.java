package com.skyhhjmk.codexcreator.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "codex.app-server")
public interface AppServerConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("codex")
    String command();

    @WithDefault(".")
    String workingDirectory();

    @WithDefault("120S")
    Duration requestTimeout();

    @WithDefault("2S")
    Duration restartBackoff();

    @WithDefault("false")
    boolean experimentalApi();

    @WithDefault("unlocked")
    String schemaVersion();
}
