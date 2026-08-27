package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.domain.AuditLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@ApplicationScoped
public class AuditLogService {
    @Inject
    ObjectMapper mapper;

    @Transactional
    public void log(String actorType, String actorId, String action, String resourceType,
                    String resourceId, String traceId, Map<String, Object> details) {
        AuditLog log = new AuditLog();
        log.actorType = actorType;
        log.actorId = actorId;
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.traceId = traceId;
        log.details = toJson(details);
        log.createdAt = OffsetDateTime.now();
        log.persist();
    }

    private String toJson(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { return "{}"; }
    }
}
