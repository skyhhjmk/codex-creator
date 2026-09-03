package com.skyhhjmk.codexcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicFingerprintTest {
    @Test
    void normalizesUnicodeCasePunctuationAndWhitespace() {
        assertEquals(
                TopicFingerprint.normalize("ＡＩ：未来！  \n  工具"),
                TopicFingerprint.normalize("ai 未来 工具"));
    }

    @Test
    void producesStableSha256Fingerprint() {
        String key = TopicFingerprint.key("AI 工具");
        assertEquals(64, key.length());
        assertEquals(key, TopicFingerprint.key(" ai  工具 "));
    }
}
