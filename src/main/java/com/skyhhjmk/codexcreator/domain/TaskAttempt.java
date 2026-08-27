package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "task_attempts")
public class TaskAttempt extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    public AutomationTask task;

    @Column(name = "attempt_number", nullable = false)
    public int attemptNumber;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(length = 80)
    public String provider;

    @Column(name = "started_at", nullable = false)
    public OffsetDateTime startedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage", columnDefinition = "jsonb")
    public String usageJson;

    @Column(name = "error_code", length = 80)
    public String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;
}
