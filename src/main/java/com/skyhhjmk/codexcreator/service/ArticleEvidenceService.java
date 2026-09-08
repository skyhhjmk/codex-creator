package com.skyhhjmk.codexcreator.service;

import com.skyhhjmk.codexcreator.domain.AiArticleJob;
import com.skyhhjmk.codexcreator.domain.ArticleJobEvidence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

/** Stores only the safe, article-eligible subset of tool evidence. */
@ApplicationScoped
public class ArticleEvidenceService {
    private static final int MAX_ARTICLE_OUTPUT = 2_000;
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]{12,}");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?im)\\b(password|passwd|token|secret|api[_-]?key)\\s*([=:])\\s*\\S+");

    @Transactional
    public void recordImage(Long jobId, String mediaUrl) {
        recordImage(jobId, mediaUrl, null, null, null, null);
    }

    @Transactional
    public void recordImage(Long jobId, String mediaUrl, String sourcePage, String licenseName, String licenseUrl, String attribution) {
        if (jobId == null || mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("article job and uploaded image URL are required");
        }
        AiArticleJob job = AiArticleJob.findById(jobId);
        if (job == null) throw new IllegalArgumentException("article job not found");
        ArticleJobEvidence evidence = new ArticleJobEvidence();
        evidence.articleJob = job;
        evidence.generationAttempt = Math.max(1, job.generationAttempt);
        evidence.kind = "IMAGE";
        evidence.mediaUrl = mediaUrl.trim();
        evidence.sourcePage = sourcePage;
        evidence.licenseName = licenseName;
        evidence.licenseUrl = licenseUrl;
        evidence.attribution = attribution;
        evidence.createdAt = OffsetDateTime.now();
        evidence.persist();
    }

    @Transactional
    public void recordVerification(Long jobId, Long serverId, String command, int exitCode, String output) {
        if (jobId == null) throw new IllegalArgumentException("article job is required");
        AiArticleJob job = AiArticleJob.findById(jobId);
        if (job == null) throw new IllegalArgumentException("article job not found");
        ArticleJobEvidence evidence = new ArticleJobEvidence();
        evidence.articleJob = job;
        evidence.generationAttempt = Math.max(1, job.generationAttempt);
        evidence.kind = "VERIFICATION";
        evidence.serverId = serverId;
        evidence.command = sanitize(command, 2_000);
        evidence.exitCode = exitCode;
        evidence.output = sanitize(output, MAX_ARTICLE_OUTPUT);
        evidence.createdAt = OffsetDateTime.now();
        evidence.persist();
    }

    public List<ArticleJobEvidence> currentEvidence(Long jobId, int generationAttempt) {
        return ArticleJobEvidence.list("articleJob.id = ?1 and generationAttempt = ?2 order by id", jobId,
                Math.max(1, generationAttempt));
    }

    static String sanitize(String value, int maxLength) {
        String safe = value == null ? "" : value;
        safe = PRIVATE_KEY.matcher(safe).replaceAll("[REDACTED PRIVATE KEY]");
        safe = BEARER.matcher(safe).replaceAll("$1[REDACTED]");
        safe = ASSIGNMENT.matcher(safe).replaceAll("$1$2[REDACTED]");
        if (safe.length() > maxLength) safe = safe.substring(0, maxLength) + "\n[output truncated]";
        return safe;
    }
}
