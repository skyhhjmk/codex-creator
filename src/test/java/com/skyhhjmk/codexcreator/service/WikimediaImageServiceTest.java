package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class WikimediaImageServiceTest {
    @Test
    void routesCommonsThroughConfiguredHttpProxy() {
        try (var client = WikimediaImageService.createClient("http://127.0.0.1:7897")) {
            var proxies = client.proxy().orElseThrow().select(URI.create("https://commons.wikimedia.org/w/api.php"));
            assertEquals(new InetSocketAddress("127.0.0.1", 7897), proxies.getFirst().address());
        }
        try (var client = WikimediaImageService.createClient("")) {
            assertTrue(client.proxy().isEmpty());
        }
    }
}
