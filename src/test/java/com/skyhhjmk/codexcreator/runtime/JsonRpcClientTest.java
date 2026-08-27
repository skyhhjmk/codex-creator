package com.skyhhjmk.codexcreator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcClientTest {
    @Test
    void matchesJsonlResponseByRequestId() throws Exception {
        Process process = new ProcessBuilder("sh", "-c",
                "IFS= read -r ignored; printf '%s\\n' '{\"id\":1,\"result\":{\"ok\":true}}'")
                .redirectErrorStream(true).start();
        ObjectMapper mapper = new ObjectMapper();
        JsonRpcClient client = new JsonRpcClient(mapper, ignored -> null);
        client.attach(process);
        try {
            assertTrue(client.request("ping", mapper.createObjectNode(), Duration.ofSeconds(2))
                    .get().path("ok").asBoolean());
        } finally {
            client.close();
        }
    }
}
