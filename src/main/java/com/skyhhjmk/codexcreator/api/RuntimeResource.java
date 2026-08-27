package com.skyhhjmk.codexcreator.api;

import com.skyhhjmk.codexcreator.service.TaskExecutionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;
import java.util.concurrent.CompletionStage;

@Path("/api/v1/runtime")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Codex Creator Runtime")
public class RuntimeResource {
    @Inject
    TaskExecutionService tasks;

    @POST
    @Path("/infer")
    @Operation(summary = "Run one idempotent AI operation")
    public CompletionStage<Response> infer(RuntimeInferenceRequest request) {
        return tasks.infer(request)
                .thenApply(result -> Response.ok(result).header("X-Task-Id", result.taskId()).build())
                .exceptionally(error -> Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", rootMessage(error))).build());
    }

    @POST
    @Path("/tasks")
    @Operation(summary = "Create a runtime task")
    public CompletionStage<Response> create(RuntimeInferenceRequest request) {
        return infer(request);
    }

    @GET
    @Path("/tasks/{id}")
    @Operation(summary = "Read a runtime task")
    public Response get(@PathParam("id") Long id) {
        RuntimeInferenceResponse result = tasks.get(id);
        return result == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(result).build();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
