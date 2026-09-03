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
@Table(name = "ai_article_jobs")
public class AiArticleJob extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "topic_id")
    public AiTopic topic;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "task_id")
    public AutomationTask task;

    @Column(name = "request_key", nullable = false, unique = true, length = 256)
    public String requestKey;

    @Column(name = "target_category_id")
    public Long targetCategoryId;

    @Column(nullable = false, length = 16)
    public String language = "zh-CN";

    @Column(columnDefinition = "text")
    public String instructions;

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @Column(name = "auto_publish_eligible", nullable = false)
    public boolean autoPublishEligible;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", columnDefinition = "jsonb", nullable = false)
    public String policySnapshot = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String provenance;

    @Column(name = "windblog_post_id")
    public Long windblogPostId;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;
}
