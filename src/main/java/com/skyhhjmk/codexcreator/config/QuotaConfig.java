package com.skyhhjmk.codexcreator.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "codex.creator.quota")
public interface QuotaConfig {
    @WithDefault("20")
    int fiveHourPauseRemainingPercent();

    @WithDefault("10")
    int weeklyPauseRemainingPercent();

    @WithDefault("20")
    int preResetWindowPercent();

    @WithDefault("60S")
    Duration refreshInterval();

    @WithDefault("60S")
    Duration pausePollInterval();
}
