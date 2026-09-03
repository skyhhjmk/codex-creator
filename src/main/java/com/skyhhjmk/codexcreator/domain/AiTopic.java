package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_topics")
public class AiTopic extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, columnDefinition = "text")
    public String title;

    @Column(columnDefinition = "text")
    public String rationale;

    @Column(nullable = false, length = 32)
    public String recommendation = "MANUAL";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String source = "{}";

    @Column(nullable = false, length = 32)
    public String status = "SUGGESTED";

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 64)
    public String dedupeKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discovery_run_id")
    public TopicDiscoveryRun discoveryRun;

    @Column(name = "last_seen_at", nullable = false)
    public OffsetDateTime lastSeenAt;

    @Column(name = "occurrence_count", nullable = false)
    public int occurrenceCount = 1;

    @Column(name = "review_note", columnDefinition = "text")
    public String reviewNote;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;
}
