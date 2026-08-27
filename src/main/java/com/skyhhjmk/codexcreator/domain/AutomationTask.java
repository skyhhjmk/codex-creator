package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "automation_tasks")
public class AutomationTask extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 80)
    public String operation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    public ModelProfile profile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id")
    public CodexThread thread;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 256)
    public String idempotencyKey;

    @Column(name = "trace_id", nullable = false, length = 160)
    public String traceId;

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb", nullable = false)
    public String inputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    public String outputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage", columnDefinition = "jsonb")
    public String usageJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb")
    public String provenanceJson;

    @Column(name = "error_code", length = 80)
    public String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "started_at")
    public OffsetDateTime startedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;

    @Column(name = "next_attempt_at")
    public OffsetDateTime nextAttemptAt;
}
