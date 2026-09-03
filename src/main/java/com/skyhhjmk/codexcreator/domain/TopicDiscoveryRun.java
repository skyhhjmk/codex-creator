package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "topic_discovery_runs")
public class TopicDiscoveryRun extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "trigger_type", nullable = false, length = 32)
    public String triggerType;

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "task_id")
    public AutomationTask task;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 256)
    public String idempotencyKey;

    @Column(name = "trace_id", nullable = false, length = 160)
    public String traceId;

    @Column(name = "seed_count", nullable = false)
    public int seedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seed_snapshot", columnDefinition = "jsonb", nullable = false)
    public String seedSnapshot = "[]";

    @Column(name = "topic_count", nullable = false)
    public int topicCount;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "started_at")
    public OffsetDateTime startedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;
}
