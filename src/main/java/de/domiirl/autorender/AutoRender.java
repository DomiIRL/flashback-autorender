package de.domiirl.autorender;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

public class AutoRender implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-autorender");
    private static final int LOAD_TIMEOUT_TICKS = 20 * 180;

    private enum Stage { WAIT_MENU, LOADING, EXPORTING, DONE }

    private final JsonArray log = new JsonArray();
    private Path dataDir;
    private Request request;
    private Stage stage = Stage.WAIT_MENU;
    private int stageTicks = 0;
    private int readyTicks = 0;
    private EditorState editorState;
    private int originalSceneIndex = -1;

    @Override
    public void onInitializeClient() {
        dataDir = FabricLoader.getInstance().getGameDir().resolve("flashback");
        Path requestFile = dataDir.resolve("autorender.json");
        if (!Files.isRegularFile(requestFile)) {
            return;
        }

        try {
            // Moved before parsing so a crash mid-render can never start a relaunch loop.
            Path running = dataDir.resolve("autorender.json.running");
            Files.move(requestFile, running, StandardCopyOption.REPLACE_EXISTING);
            request = Request.parse(Files.readString(running));
        } catch (Exception e) {
            finish(false, "bad request: " + e);
            stage = Stage.DONE;
            ClientTickEvents.END_CLIENT_TICK.register(mc -> mc.stop());
            return;
        }
        step("request " + request);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(request.timeoutSeconds() * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            finish(false, "timed out after " + request.timeoutSeconds() + "s in stage " + stage);
            // The main thread may be stuck inside the blocking export loop, so a clean stop cannot run.
            Runtime.getRuntime().halt(2);
        }, "autorender-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (stage == Stage.DONE) {
            return;
        }
        stageTicks++;
        try {
            switch (stage) {
                case WAIT_MENU -> {
                    boolean menuIdle = mc.gui.overlay() == null && mc.gui.screen() != null && mc.level == null;
                    readyTicks = menuIdle ? readyTicks + 1 : 0;
                    if (readyTicks >= 40) {
                        if (!Files.isRegularFile(request.replay())) {
                            throw new IllegalStateException("replay not found: " + request.replay());
                        }
                        step("opening replay");
                        enter(Stage.LOADING);
                        Flashback.openReplayWorld(request.replay());
                    }
                }
                case LOADING -> {
                    boolean loaded = Flashback.getReplayServer() != null && mc.player != null && mc.level != null
                        && mc.gui.screen() == null && EditorStateManager.getCurrent() != null;
                    readyTicks = loaded ? readyTicks + 1 : 0;
                    // Give chunks a few seconds to arrive before the first frame is captured.
                    if (readyTicks >= 100) {
                        startExport(mc);
                        enter(Stage.EXPORTING);
                    } else if (stageTicks > LOAD_TIMEOUT_TICKS) {
                        throw new IllegalStateException("replay did not finish loading");
                    }
                }
                case EXPORTING -> {
                    // ExportJob.run() blocks the main thread, so ticks resume only once the export is over.
                    if (Flashback.EXPORT_JOB != null) {
                        return;
                    }
                    restoreScene();
                    Path out = request.output();
                    if (Files.isRegularFile(out) && Files.size(out) > 0) {
                        finish(true, "exported " + Files.size(out) + " bytes");
                    } else {
                        finish(false, "export ended without output at " + out);
                    }
                    stage = Stage.DONE;
                    mc.stop();
                }
                default -> {}
            }
        } catch (Exception e) {
            LOGGER.error("autorender failed", e);
            restoreScene();
            finish(false, e.toString());
            stage = Stage.DONE;
            mc.stop();
        }
    }

    private void startExport(Minecraft mc) throws Exception {
        editorState = EditorStateManager.getCurrent();
        backupEditorState();

        long stamp = editorState.acquireWrite();
        try {
            List<EditorScene> scenes = editorState.getScenes(stamp);
            int index = -1;
            for (int i = 0; i < scenes.size(); i++) {
                if (scenes.get(i).name.equals(request.scene())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("scene \"" + request.scene() + "\" not found, have "
                    + scenes.stream().map(s -> s.name).toList());
            }
            originalSceneIndex = editorState.getSceneIndex();
            editorState.setSceneIndex(index, stamp);
        } finally {
            editorState.release(stamp);
        }

        // Mirrors StartExportWindow.createExportSettings: scene export range first, keyframe range as fallback.
        int totalTicks = Flashback.getReplayServer().getTotalReplayTicks();
        var range = editorState.getExportStartAndEnd();
        int start = range.start(), end = range.end();
        if (start < 0 || end < 0) {
            var tracks = editorState.getFirstAndLastTicksInTracks();
            start = Math.max(0, tracks.start());
            end = tracks.end() < 0 || tracks.end() == start ? totalTicks : tracks.end();
        }
        start = Math.min(Math.max(0, start), totalTicks);
        end = Math.min(Math.max(start, end), totalTicks);

        FlashbackConfigV1.SubcategoryInternalExport cfg = Flashback.getConfig().internalExport;
        int width = request.width() != null ? request.width() : cfg.resolution[0];
        int height = request.height() != null ? request.height() : cfg.resolution[1];
        double framerate = request.framerate() != null ? request.framerate() : cfg.framerate[0];
        // StartExportWindow keeps the bitrate only in memory with "20m" as its default.
        int bitrate = cfg.useMaximumBitrate ? 0 : Request.bitrateToInt(request.bitrate() != null ? request.bitrate() : "20m");

        boolean transparent = cfg.transparentBackground && !editorState.replayVisuals.renderSky;
        VideoCodec codec = cfg.videoCodec;
        VideoCodec[] codecs = cfg.container.getSupportedVideoCodecs(transparent);
        if (codec == null || !Arrays.asList(codecs).contains(codec)) {
            codec = codecs[0];
        }
        String[] encoders = codec.getEncoders();
        String encoder = null;
        if (encoders != null && encoders.length > 0) {
            encoder = Arrays.asList(encoders).contains(cfg.selectedVideoEncoder) ? cfg.selectedVideoEncoder : encoders[0];
        }
        AudioCodec audio = cfg.recordAudio && cfg.container.getSupportedAudioCodecs().length > 0 ? cfg.audioCodec : null;

        Files.createDirectories(request.output().toAbsolutePath().getParent());
        ExportSettings settings = new ExportSettings(null, editorState.copy(),
            mc.player.position(), mc.player.getYRot(), mc.player.getXRot(),
            width, height, start, end,
            cfg.projection, cfg.orthographicZoom[0],
            Math.max(1, framerate), cfg.resetRng, cfg.depthMap,
            cfg.container, codec, encoder, bitrate, transparent, cfg.ssaa, cfg.noGui,
            cfg.stereoAudio, audio,
            request.output().toAbsolutePath(), "%04d");

        step("export scene " + request.scene() + " ticks " + start + "-" + end + " " + width + "x" + height
            + "@" + framerate + " " + cfg.container + "/" + codec + "/" + encoder + " bitrate " + bitrate);
        Utils.exportSequenceCount += 1;
        Flashback.EXPORT_JOB = new ExportJob(settings);
    }

    private void backupEditorState() throws Exception {
        try (var files = Files.list(dataDir.resolve("editor_states"))) {
            Path backups = dataDir.resolve("autorender-backups");
            Files.createDirectories(backups);
            String stamp = String.valueOf(System.currentTimeMillis());
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                Files.copy(p, backups.resolve(p.getFileName() + "." + stamp), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void restoreScene() {
        if (editorState == null || originalSceneIndex < 0) {
            return;
        }
        long stamp = editorState.acquireWrite();
        try {
            editorState.setSceneIndex(originalSceneIndex, stamp);
        } finally {
            editorState.release(stamp);
        }
        originalSceneIndex = -1;
        // A scene switch does not mark the state dirty, and an autosave during the export already wrote
        // the rendered scene as selected; reset() saves unconditionally. Only safe right before quitting.
        EditorStateManager.reset();
    }

    private void enter(Stage next) {
        stage = next;
        stageTicks = 0;
        readyTicks = 0;
    }

    private void step(String message) {
        LOGGER.info(message);
        synchronized (log) {
            log.add(message);
        }
    }

    private synchronized void finish(boolean ok, String message) {
        step(message);
        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("message", message);
        if (request != null) {
            result.addProperty("output", request.output().toAbsolutePath().toString());
        }
        synchronized (log) {
            result.add("log", log.deepCopy());
        }
        try {
            Files.writeString(dataDir.resolve("autorender-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
            Files.deleteIfExists(dataDir.resolve("autorender.json.running"));
        } catch (Exception e) {
            LOGGER.error("could not write autorender result", e);
        }
    }
}
