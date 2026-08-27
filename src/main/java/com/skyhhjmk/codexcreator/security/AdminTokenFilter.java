package com.skyhhjmk.codexcreator.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AdminTokenFilter implements ContainerRequestFilter {
    @ConfigProperty(name = "codex.creator.admin-token")
    String adminToken;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String path = context.getUriInfo().getPath();
        if (!path.startsWith("api/admin/") || path.equals("api/admin/auth/status")) {
            return;
        }
        String authorization = context.getHeaderString("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : "";
        if (adminToken == null || adminToken.isBlank() || !same(adminToken, supplied)) {
            context.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .entity(java.util.Map.of("error", "admin authentication required"))
                    .build());
        }
    }

    private static boolean same(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
