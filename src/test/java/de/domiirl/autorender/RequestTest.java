package de.domiirl.autorender;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RequestTest {

    @Test
    void parsesMinimalRequestWithDefaults() {
        Request r = Request.parse("{\"replay\":\"/a/r.zip\",\"scene\":\"drueber\",\"output\":\"/a/o.mp4\"}");
        assertEquals(Path.of("/a/r.zip"), r.replay());
        assertEquals("drueber", r.scene());
        assertEquals(Path.of("/a/o.mp4"), r.output());
        assertEquals(1800, r.timeoutSeconds());
        assertNull(r.width());
        assertNull(r.bitrate());
    }

    @Test
    void parsesSettingsOverrides() {
        Request r = Request.parse("{\"replay\":\"r\",\"scene\":\"s\",\"output\":\"o\",\"timeoutSeconds\":60,"
            + "\"settings\":{\"width\":1280,\"height\":720,\"framerate\":30,\"bitrate\":\"8m\"}}");
        assertEquals(60, r.timeoutSeconds());
        assertEquals(1280, r.width());
        assertEquals(720, r.height());
        assertEquals(30f, r.framerate());
        assertEquals(8_000_000, Request.bitrateToInt(r.bitrate()));
    }

    @Test
    void rejectsMissingScene() {
        assertThrows(IllegalArgumentException.class,
            () -> Request.parse("{\"replay\":\"r\",\"output\":\"o\"}"));
    }

    @Test
    void bitrateGrammarMatchesFlashback() {
        assertEquals(20_000_000, Request.bitrateToInt("20m"));
        assertEquals(1_500_000, Request.bitrateToInt("1m500k"));
    }
}
