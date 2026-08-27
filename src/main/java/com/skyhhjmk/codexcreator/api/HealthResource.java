package com.skyhhjmk.codexcreator.api;

import com.skyhhjmk.codexcreator.runtime.CodexAppServerSupervisor;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/internal/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @Inject
    CodexAppServerSupervisor supervisor;

    @GET
    public Map<String, Object> status() {
        return supervisor.status();
    }
}
