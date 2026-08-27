package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class CacheKey {
    private CacheKey() {
    }

    public static String forTask(String operation, String input, String profileId, String promptVersion) {
        String canonical = String.join("\n", safe(operation), safe(input), safe(profileId), safe(promptVersion));
        try {
            return "codex:task:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create task cache key", exception);
        }
    }

    public static String forTask(ObjectMapper mapper, String operation, JsonNode input,
                                 String profileId, String promptVersion) {
        try {
            return forTask(operation, mapper.writeValueAsString(input), profileId, promptVersion);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize task cache input", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
