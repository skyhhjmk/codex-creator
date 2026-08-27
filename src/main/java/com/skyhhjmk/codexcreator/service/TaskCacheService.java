package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Optional;

/** Best-effort Redis cache and lock primitives; PostgreSQL remains the source of truth. */
@ApplicationScoped
public class TaskCacheService {
    private static final String PREFIX = "codex-creator:";

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    ObjectMapper mapper;

    private volatile ValueCommands<String, String> values;
    private volatile KeyCommands<String> keys;

    public Optional<JsonNode> get(String cacheKey) {
        if (!connect()) return Optional.empty();
        try {
            String value = values.get(PREFIX + cacheKey);
            return value == null ? Optional.empty() : Optional.ofNullable(mapper.readTree(value));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void put(String cacheKey, JsonNode output, Duration ttl) {
        if (output == null || output.isNull() || !connect()) return;
        try {
            values.set(PREFIX + cacheKey, mapper.writeValueAsString(output),
                    new SetArgs().ex(ttl == null ? Duration.ofMinutes(30) : ttl));
        } catch (Exception ignored) {
            // A cache outage must not fail an otherwise durable task.
        }
    }

    /** Returns a token when the distributed lock was acquired. */
    public Optional<String> tryAcquire(String lockKey, Duration ttl) {
        if (!connect()) return Optional.empty();
        String token = java.util.UUID.randomUUID().toString();
        try {
            boolean acquired = values.setAndChanged(PREFIX + "lock:" + lockKey, token,
                    new SetArgs().nx().ex(ttl == null ? Duration.ofSeconds(30) : ttl));
            return acquired ? Optional.of(token) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Releases only the caller's lock token. */
    public void release(String lockKey, String token) {
        if (token == null || !connect()) return;
        try {
            String key = PREFIX + "lock:" + lockKey;
            if (token.equals(values.get(key))) keys.del(key);
        } catch (Exception ignored) {
            // Lock expiry is the safety net when Redis is unavailable.
        }
    }

    private boolean connect() {
        if (values != null && keys != null) return true;
        synchronized (this) {
            if (values != null && keys != null) return true;
            try {
                RedisDataSource source = redisDataSource.get();
                values = source.value(String.class);
                keys = source.key(String.class);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
