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
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "model_profile_id")
    public ModelProfile modelProfile;

    @Column(name = "request_key", nullable = false, unique = true, length = 256)
    public String requestKey;

    @Column(name = "target_category_id")
    public Long targetCategoryId;

    @Column(nullable = false, length = 16)
    public String language = "zh-CN";

    @Column(columnDefinition = "text")
    public String instructions;

    @Column(name = "reasoning_effort", nullable = false, length = 16)
    public String reasoningEffort = "high";

    @Column(name = "requires_practical_verification", nullable = false)
    public boolean requiresPracticalVerification;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "ai_article_job_test_servers",
            joinColumns = @JoinColumn(name = "article_job_id"),
            inverseJoinColumns = @JoinColumn(name = "test_server_id"))
    public Set<TestServer> testServers = new LinkedHashSet<>();

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

    @Column(name = "generation_attempt", nullable = false)
    public int generationAttempt = 1;

    @Column(name = "quality_attempt", nullable = false)
    public int qualityAttempt = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_report", columnDefinition = "jsonb")
    public String qualityReport;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;
}
