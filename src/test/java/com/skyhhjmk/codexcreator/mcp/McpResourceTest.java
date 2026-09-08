package com.skyhhjmk.codexcreator.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyhhjmk.codexcreator.service.WikimediaImageService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class McpResourceTest {
    @Test
    void thumbnailFilenameUsesDownloadedMimeType() {
        assertEquals("Diagram.png", McpResource.safeFilename("File:Diagram.svg", "image/png"));
        assertEquals("Photo.jpg", McpResource.safeFilename("File:Photo.tiff", "image/jpeg"));
        assertEquals("Animation.gif", McpResource.safeFilename("File:Animation", "image/gif"));
    }

    @Test
    void requiresInspectionOfSameBytesAndJobBeforeImport() {
        McpResource resource = new McpResource() {
            @Override boolean profileAllows(String operation) { return true; }
        };
        resource.mapper = new ObjectMapper();
        resource.bearerToken = Optional.of("test-token");
        byte[][] bytes = {new byte[]{1, 2, 3}};
        resource.wikimediaImages = new WikimediaImageService() {
            @Override public ImageBytes download(String title) {
                return new ImageBytes(new Candidate(title, "https://commons.wikimedia.org/wiki/File:Test.png",
                        "https://upload.wikimedia.org/test.png", "CC0", "https://creativecommons.org/publicdomain/zero/1.0/", "Author"),
                        bytes[0], "image/png");
            }
        };
        assertEquals(-32002, call(resource, "import", 1).path("error").path("code").asInt());
        assertTrue(call(resource, "inspect", 1).has("result"));
        assertEquals(-32002, call(resource, "import", 2).path("error").path("code").asInt());
        bytes[0] = new byte[]{4, 5, 6};
        assertEquals(-32002, call(resource, "import", 1).path("error").path("code").asInt());
        assertEquals(401, resource.post("{}", null).getStatus());
    }

    private JsonNode call(McpResource resource, String action, int jobId) {
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                  "name":"windblog.%s_wikimedia_image","arguments":{"articleJobId":%d,"title":"File:Test.png"}}}
                """.formatted(action, jobId);
        return (JsonNode) resource.post(request, "Bearer test-token").getEntity();
    }
}
