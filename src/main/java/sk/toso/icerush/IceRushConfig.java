package sk.toso.icerush;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

final class IceRushConfig {
    int minPlayers = 2;
    int maxPlayers = 16;
    int countdownSeconds = 3;
    int minRideSeconds = 25;
    int maxRideSeconds = 40;
    int parkingSeconds = 20;
    int finishHoldSeconds = 8;
    int lobbyHeight = 80;
    double trackInnerRadius = 38.0;
    double trackOuterRadius = 45.0;
    int turboZones = 6;
    int turboDurationSeconds = 2;
    int turboBonusPoints = 2;
    int eventChancePercent = 5;
    int crackChancePercent = 10;
    int crackWarningSeconds = 2;
    int speedGraceSeconds = 5;
    int minimumSpeedPercent = 60;
    double speedReference = 0.30;
    double parkingRadius = 2.5;
    boolean arenaConfigured = false;
    String arenaDimension = "";
    int arenaX;
    int arenaY;
    int arenaZ;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private transient final Path file = FabricLoader.getInstance().getConfigDir().resolve("icerush.json");

    static IceRushConfig load() {
        IceRushConfig config = new IceRushConfig();
        config.read();
        config.validate();
        return config;
    }

    private void read() {
        if (!Files.exists(file)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            IceRushConfig loaded = GSON.fromJson(reader, IceRushConfig.class);
            if (loaded != null) copyFrom(loaded);
        } catch (Exception exception) {
            IceRushMod.LOGGER.error("Could not read ICE RUSH config {}; using defaults", file, exception);
        }
    }

    private void copyFrom(IceRushConfig loaded) {
        minPlayers = loaded.minPlayers;
        maxPlayers = loaded.maxPlayers;
        countdownSeconds = loaded.countdownSeconds;
        minRideSeconds = loaded.minRideSeconds;
        maxRideSeconds = loaded.maxRideSeconds;
        parkingSeconds = loaded.parkingSeconds;
        finishHoldSeconds = loaded.finishHoldSeconds;
        lobbyHeight = loaded.lobbyHeight;
        trackInnerRadius = loaded.trackInnerRadius;
        trackOuterRadius = loaded.trackOuterRadius;
        turboZones = loaded.turboZones;
        turboDurationSeconds = loaded.turboDurationSeconds;
        turboBonusPoints = loaded.turboBonusPoints;
        eventChancePercent = loaded.eventChancePercent;
        crackChancePercent = loaded.crackChancePercent;
        crackWarningSeconds = loaded.crackWarningSeconds;
        speedGraceSeconds = loaded.speedGraceSeconds;
        minimumSpeedPercent = loaded.minimumSpeedPercent;
        speedReference = loaded.speedReference;
        parkingRadius = loaded.parkingRadius;
        arenaConfigured = loaded.arenaConfigured;
        arenaDimension = loaded.arenaDimension == null ? "" : loaded.arenaDimension;
        arenaX = loaded.arenaX;
        arenaY = loaded.arenaY;
        arenaZ = loaded.arenaZ;
    }

    private void validate() {
        minPlayers = clamp(minPlayers, 2, 16);
        maxPlayers = clamp(maxPlayers, minPlayers, 16);
        countdownSeconds = clamp(countdownSeconds, 1, 30);
        minRideSeconds = clamp(minRideSeconds, 5, 600);
        maxRideSeconds = clamp(maxRideSeconds, minRideSeconds, 600);
        parkingSeconds = clamp(parkingSeconds, 5, 120);
        finishHoldSeconds = clamp(finishHoldSeconds, 1, 60);
        lobbyHeight = clamp(lobbyHeight, 20, 200);
        trackInnerRadius = Math.max(5.0, Math.min(trackInnerRadius, 100.0));
        trackOuterRadius = Math.max(trackInnerRadius + 1.0, Math.min(trackOuterRadius, 120.0));
        turboZones = clamp(turboZones, 0, 32);
        turboDurationSeconds = clamp(turboDurationSeconds, 1, 15);
        turboBonusPoints = clamp(turboBonusPoints, 0, 100);
        eventChancePercent = clamp(eventChancePercent, 0, 100);
        crackChancePercent = clamp(crackChancePercent, 0, 100);
        crackWarningSeconds = clamp(crackWarningSeconds, 1, 10);
        speedGraceSeconds = clamp(speedGraceSeconds, 0, 30);
        minimumSpeedPercent = clamp(minimumSpeedPercent, 60, 100);
        minimumSpeedPercent = 60 + Math.round((minimumSpeedPercent - 60) / 5.0F) * 5;
        speedReference = Math.max(0.05, Math.min(speedReference, 2.0));
        parkingRadius = Math.max(1.0, Math.min(parkingRadius, 10.0));
        save();
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception exception) {
            IceRushMod.LOGGER.error("Could not save ICE RUSH config to {}", file, exception);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
