package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TestServerServiceTest {
    @Test
    void drainsOutputBeyondPipeCapacityWithoutUnboundedRetention() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "head -c 1048576 /dev/zero").start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> TestServerService.drainOutput(process.getInputStream(), output));
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            reader.join(5000);
            assertFalse(reader.isAlive());
            assertEquals(0, process.exitValue());
            assertEquals(16384, output.size());
        } finally {
            process.destroyForcibly();
        }
    }
}
