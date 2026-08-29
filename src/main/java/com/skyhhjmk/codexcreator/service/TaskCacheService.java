package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
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
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    public enum LockStatus {
        ACQUIRED,
        BUSY,
        UNAVAILABLE
    }

    public record LockAttempt(LockStatus status, String token) {
        public static LockAttempt acquired(String token) {
            return new LockAttempt(LockStatus.ACQUIRED, token);
        }

        public static LockAttempt busy() {
            return new LockAttempt(LockStatus.BUSY, null);
        }

        public static LockAttempt unavailable() {
            return new LockAttempt(LockStatus.UNAVAILABLE, null);
        }
    }

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    ObjectMapper mapper;

    private volatile RedisDataSource source;
    private volatile ValueCommands<String, String> values;

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

    /** Distinguishes a held lock from an unavailable Redis cache. */
    public LockAttempt acquire(String lockKey, Duration ttl) {
        if (!connect()) return LockAttempt.unavailable();
        String token = java.util.UUID.randomUUID().toString();
        try {
            boolean acquired = values.setAndChanged(PREFIX + "lock:" + lockKey, token,
                    new SetArgs().nx().ex(ttl == null ? Duration.ofSeconds(30) : ttl));
            return acquired ? LockAttempt.acquired(token) : LockAttempt.busy();
        } catch (Exception ignored) {
            return LockAttempt.unavailable();
        }
    }

    /** Releases only the caller's lock token. */
    public void release(String lockKey, String token) {
        if (token == null || !connect()) return;
        try {
            String key = PREFIX + "lock:" + lockKey;
            // Compare-and-delete must be one Redis operation; a separate GET
            // followed by DEL could remove a newer owner's lock.
            source.execute("EVAL", RELEASE_LOCK_SCRIPT, "1", key, token);
        } catch (Exception ignored) {
            // Lock expiry is the safety net when Redis is unavailable.
        }
    }

    private boolean connect() {
        if (source != null && values != null) return true;
        synchronized (this) {
            if (source != null && values != null) return true;
            try {
                source = redisDataSource.get();
                values = source.value(String.class);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
