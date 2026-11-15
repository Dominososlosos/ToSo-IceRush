package sk.toso.icerush;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class IceRushStatsStore {
    static final class PlayerStats {
        int wins;
        int gamesPlayed;
        int losses;
        int points;
        int turboBonuses;
        double speedRecord;
        int bestParkingTicks;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, PlayerStats>>() {}.getType();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("icerush-stats.json");
    private final Map<UUID, PlayerStats> data = new LinkedHashMap<>();

    IceRushStatsStore() {
        load();
    }

    synchronized PlayerStats get(UUID playerId) {
        return data.computeIfAbsent(playerId, ignored -> new PlayerStats());
    }

    synchronized List<Map.Entry<UUID, PlayerStats>> top(int limit) {
        return data.entrySet().stream()
            .sorted(Map.Entry.<UUID, PlayerStats>comparingByValue(Comparator.comparingInt((PlayerStats s) -> s.points).reversed()
                .thenComparing(Comparator.comparingInt((PlayerStats s) -> s.wins).reversed())))
            .limit(limit)
            .toList();
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, PlayerStats> serializable = new LinkedHashMap<>();
            data.forEach((id, stats) -> serializable.put(id.toString(), stats));
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(serializable, writer);
            }
        } catch (Exception exception) {
            IceRushMod.LOGGER.error("Could not save ICE RUSH statistics to {}", file, exception);
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, PlayerStats> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                loaded.forEach((id, stats) -> {
                    try {
                        data.put(UUID.fromString(id), stats == null ? new PlayerStats() : stats);
                    } catch (IllegalArgumentException ignored) {
                        IceRushMod.LOGGER.warn("Ignoring invalid player UUID in ICE RUSH statistics: {}", id);
                    }
                });
            }
            IceRushMod.LOGGER.info("Loaded {} ICE RUSH player statistics", data.size());
        } catch (Exception exception) {
            IceRushMod.LOGGER.error("Could not load ICE RUSH statistics from {}", file, exception);
        }
    }
}
