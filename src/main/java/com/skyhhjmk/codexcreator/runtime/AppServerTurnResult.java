package com.skyhhjmk.codexcreator.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Final turn response plus the authoritative completed items emitted by app-server. */
public record AppServerTurnResult(JsonNode completed, List<ObjectNode> completedItems) {
    public AppServerTurnResult {
        completedItems = completedItems == null ? List.of() : List.copyOf(completedItems);
    }
}
