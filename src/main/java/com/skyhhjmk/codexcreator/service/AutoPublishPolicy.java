package com.skyhhjmk.codexcreator.service;

/** All gates must pass before an AI-created article may be published automatically. */
public record AutoPublishPolicy(boolean globallyEnabled, boolean topicAllowsWriting,
                                boolean categoryAllowsAi, boolean dailyQuotaAvailable,
                                boolean checksPassed, boolean hasProvenance,
                                boolean manuallyPaused) {
    public boolean eligible() {
        return globallyEnabled && topicAllowsWriting && categoryAllowsAi
                && dailyQuotaAvailable && checksPassed && hasProvenance && !manuallyPaused;
    }
}
