package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "actor_type", nullable = false, length = 32)
    public String actorType;

    @Column(name = "actor_id", length = 160)
    public String actorId;

    @Column(nullable = false, length = 128)
    public String action;

    @Column(name = "resource_type", length = 80)
    public String resourceType;

    @Column(name = "resource_id", length = 160)
    public String resourceId;

    @Column(name = "trace_id", length = 160)
    public String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String details = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
}
