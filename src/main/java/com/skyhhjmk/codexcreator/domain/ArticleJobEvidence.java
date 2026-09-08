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

import java.time.OffsetDateTime;

/** Auditable evidence produced during one concrete article-generation attempt. */
@Entity
@Table(name = "ai_article_job_evidence")
public class ArticleJobEvidence extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_job_id", nullable = false)
    public AiArticleJob articleJob;

    @Column(name = "generation_attempt", nullable = false)
    public int generationAttempt;

    @Column(nullable = false, length = 32)
    public String kind;

    @Column(name = "media_url", length = 2048)
    public String mediaUrl;

    @Column(name = "source_page", length = 2048)
    public String sourcePage;

    @Column(name = "license_name", length = 512)
    public String licenseName;

    @Column(name = "license_url", length = 2048)
    public String licenseUrl;

    @Column(name = "attribution", columnDefinition = "text")
    public String attribution;

    @Column(name = "server_id")
    public Long serverId;

    @Column(columnDefinition = "text")
    public String command;

    @Column(name = "exit_code")
    public Integer exitCode;

    @Column(columnDefinition = "text")
    public String output;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
}
