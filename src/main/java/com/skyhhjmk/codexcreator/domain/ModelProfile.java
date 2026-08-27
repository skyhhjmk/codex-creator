package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "model_profiles")
public class ModelProfile extends PanacheEntityBase {
    @Id
    @Column(name = "profile_id", length = 128)
    public String profileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_config_id")
    public ProviderConfig providerConfig;

    @Column(nullable = false, length = 80)
    public String vendor;

    @Column(name = "model_id", nullable = false, length = 160)
    public String modelId;

    @Column(name = "display_name", nullable = false, length = 200)
    public String displayName;

    @Column(name = "reasoning_effort", length = 32)
    public String reasoningEffort;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String capabilities = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_operations", columnDefinition = "jsonb", nullable = false)
    public String allowedOperations = "[]";

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "discovered_at")
    public OffsetDateTime discoveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
