package com.skyhhjmk.codexcreator.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppServerJsonlFixtureTest {
    @Test
    void receivesWebSearchAgentMessageAndTurnCompletionAndDeclinesApproval() throws Exception {
        String fixture = """
                IFS= read -r ignored
                printf '%s\\n' '{"id":99,"method":"item/approval/request","params":{}}'
                IFS= read -r decision
                printf '%s\\n' '{"id":1,"result":{"turn":{"id":"turn-1"}}}'
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"webSearch","id":"search-1","query":"AI 工具","action":{"type":"search"}}}}'
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"message-1","text":"{\\"topics\\":[{\\"title\\":\\"测试\\"}]}"}}}'
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"other-thread","turnId":"other-turn","item":{"type":"webSearch","id":"other","query":"ignore"}}}'
                """;
        Process process = new ProcessBuilder("sh", "-c", fixture)
                .redirectErrorStream(true).start();
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<ObjectNode> serverRequest = new AtomicReference<>();
        List<ObjectNode> notifications = new CopyOnWriteArrayList<>();
        JsonRpcClient client = new JsonRpcClient(mapper, request -> {
            serverRequest.set(request);
            return mapper.createObjectNode().put("decision", "decline");
        });
        client.attach(process);
        client.addNotificationListener(notifications::add);
        try {
            JsonNode result = client.request("turn/start", mapper.createObjectNode(), Duration.ofSeconds(2)).get();

            assertEquals("turn-1", result.path("turn").path("id").asText());
            assertEquals("item/approval/request", serverRequest.get().path("method").asText());
            assertEquals(3, notifications.size());
            assertEquals("webSearch", notifications.get(0).path("params").path("item").path("type").asText());
            assertEquals("agentMessage", notifications.get(1).path("params").path("item").path("type").asText());
            assertEquals("other-thread", notifications.get(2).path("params").path("threadId").asText());
        } finally {
            client.close();
        }
    }
}
