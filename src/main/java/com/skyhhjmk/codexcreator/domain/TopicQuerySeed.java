package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "topic_query_seeds")
public class TopicQuerySeed extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 120)
    public String name;

    @Column(nullable = false, columnDefinition = "text")
    public String query;

    @Column(nullable = false, length = 16)
    public String language = "zh-CN";

    @Column(length = 64)
    public String region;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    public int sortOrder;

    @Column(name = "last_used_at")
    public OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
