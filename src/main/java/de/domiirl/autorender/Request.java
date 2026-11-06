package de.domiirl.autorender;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;

/**
 * Overrides are null when the request leaves them out; Flashback's last export settings apply then.
 */
public record Request(Path replay, String scene, Path output, int timeoutSeconds,
                      Integer width, Integer height, Float framerate, String bitrate) {

    public static Request parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonObject s = o.has("settings") ? o.getAsJsonObject("settings") : new JsonObject();
        return new Request(
            Path.of(required(o, "replay")),
            required(o, "scene"),
            Path.of(required(o, "output")),
            o.has("timeoutSeconds") ? o.get("timeoutSeconds").getAsInt() : 1800,
            s.has("width") ? s.get("width").getAsInt() : null,
            s.has("height") ? s.get("height").getAsInt() : null,
            s.has("framerate") ? s.get("framerate").getAsFloat() : null,
            s.has("bitrate") ? s.get("bitrate").getAsString() : null);
    }

    private static String required(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("request is missing \"" + key + "\"");
        }
        return o.get(key).getAsString();
    }

    // Same grammar as Flashback's bitrate field: "20m", "500k", "1m500k".
    public static int bitrateToInt(String s) {
        long total = 0, number = 0, modifier = 1;
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                if (modifier > 1) {
                    total += number * modifier;
                    number = 0;
                    modifier = 1;
                }
                number = number * 10 + (c - '0');
            } else if (c == 'k') {
                modifier *= 1000;
            } else if (c == 'm') {
                modifier *= 1_000_000;
            } else if (c == 'b') {
                modifier *= 1_000_000_000;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total + number * modifier);
    }
}
