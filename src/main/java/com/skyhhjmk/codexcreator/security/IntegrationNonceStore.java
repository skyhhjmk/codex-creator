package com.skyhhjmk.codexcreator.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Persists signed-request nonces so replay protection survives restarts and
 * works when more than one Codex Creator instance handles requests.
 */
@ApplicationScoped
public class IntegrationNonceStore {
    @Inject
    EntityManager entityManager;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean claim(String clientId, String nonce, long expiryEpochSeconds) {
        entityManager.createNativeQuery("DELETE FROM integration_nonces WHERE expires_at < CURRENT_TIMESTAMP")
                .executeUpdate();

        entityManager.createNativeQuery("""
                INSERT INTO integration_nonces (nonce, client_id, expires_at)
                VALUES (:nonce, :clientId, :expiresAt)
                """)
                .setParameter("nonce", nonce)
                .setParameter("clientId", clientId)
                .setParameter("expiresAt", OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(expiryEpochSeconds), ZoneOffset.UTC))
                .executeUpdate();
        return true;
    }
}
