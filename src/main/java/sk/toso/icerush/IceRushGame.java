package sk.toso.icerush;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.util.Identifier;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.ClickEvent;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class IceRushGame {
    private enum State { WAITING, COUNTDOWN, RACING, PARKING, FINISHED }


    private final IceRushConfig config = IceRushConfig.load();
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final IceRushStatsStore statsStore = new IceRushStatsStore();
    private boolean resultsCommitted;
    private final List<Vec3d> parkingSpots = new ArrayList<>();
    private final Set<Integer> occupiedSpots = new HashSet<>();
    private final Set<UUID> lateSpectators = new HashSet<>();
    private final Map<UUID, BoatEntity> playerBoats = new LinkedHashMap<>();
    private final Map<UUID, ServerBossBar> speedBars = new LinkedHashMap<>();
    private final ServerBossBar phaseBar = new ServerBossBar(Text.literal("ICE RUSH"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
    private final List<Vec3d> startPositions = new ArrayList<>();
    private final List<Vec3d> turboZones = new ArrayList<>();
    private final List<BlockPos> crackPositions = new ArrayList<>();
    private final Set<BlockPos> openCracks = new HashSet<>();
    private final Map<BlockPos, Integer> warningCracks = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> parkingOriginalStates = new LinkedHashMap<>();
    private String roundEvent = "NONE";
    private ServerWorld arenaWorld;
    private BlockPos arenaCenter;
    private Vec3d lobbyPosition;
    private boolean countdownNeedsValidation;
    private boolean boatsLocked;
    private boolean lobbyCountdown;
    private State state = State.WAITING;
    private int timer;
    private int round;
    private int roundTicks;
    private UUID winner;

    private static final class Participant {
        private final UUID id;
        private String playerName = "";
        private boolean ready;
        private boolean eliminated;
        private boolean parked;
        private int parkingSpot = -1;
        private int points;
        private int turboTicks;
        private boolean turboActive;
        private int turboBonuses;
        private int parkingTimeTicks;
        private int bestParkingTicks;
        private double speedRecord;
        private int speedPercent = 100;
        private final Set<Integer> turboZonesUsed = new HashSet<>();

        private Participant(UUID id) {
            this.id = id;
        }
    }

    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                 net.minecraft.command.CommandRegistryAccess registryAccess,
                                 CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("icerush")
            .then(CommandManager.literal("join").executes(ctx -> join(ctx.getSource())))
            .then(CommandManager.literal("leave").executes(ctx -> leave(ctx.getSource())))
            .then(CommandManager.literal("ready").executes(ctx -> ready(ctx.getSource())))
            .then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
            .then(CommandManager.literal("stats").executes(ctx -> showStats(ctx.getSource())))
            .then(CommandManager.literal("top").executes(ctx -> showTop(ctx.getSource())))
            .then(CommandManager.literal("achievements").executes(ctx -> showAchievements(ctx.getSource())))
            .then(CommandManager.literal("lastwinner").executes(ctx -> showLastWinner(ctx.getSource())))
            .then(CommandManager.literal("rules").executes(ctx -> showRules(ctx.getSource())))
            .then(CommandManager.literal("start").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> start(ctx.getSource())))
            .then(CommandManager.literal("reset").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> reset(ctx.getSource())))
            .then(CommandManager.literal("addspot").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> addSpot(ctx.getSource())))
            .then(CommandManager.literal("clearspots").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> clearSpots(ctx.getSource())))
            .then(CommandManager.literal("setup").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> setupArena(ctx.getSource())))
        );
    }

    private int join(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Participant existing = participants.get(player.getUuid());
        if (existing != null) {
            player.sendMessage(Text.literal(existing.eliminated ? "Túto hru už sleduješ ako vyradený hráč." : "Už si prihlásený do ICE RUSH."), false);
            return 0;
        }
        if (state != State.WAITING) {
            lateSpectators.add(player.getUuid());
            phaseBar.addPlayer(player);
            player.changeGameMode(GameMode.SPECTATOR);
            teleportToLobby(player);
            player.sendMessage(Text.literal("Hra už prebieha. Sleduješ ju a do hry sa zapojíš v ďalšom kole.").formatted(Formatting.YELLOW), false);
            return 1;
        }
        if (participants.size() >= config.maxPlayers) {
            player.sendMessage(Text.literal("ICE RUSH je plný (" + config.maxPlayers + " hráčov).").formatted(Formatting.RED), false);
            return 0;
        }
        Participant participant = new Participant(player.getUuid());
        participant.playerName = player.getName().getString();
        participants.put(player.getUuid(), participant);
        teleportToLobby(player);
        player.changeGameMode(GameMode.ADVENTURE);
        sendLobbyMenu(player);
        broadcast(source.getServer(), Text.literal(player.getName().getString() + " sa pripojil do ICE RUSH (" + participants.size() + "/" + config.maxPlayers + ").").formatted(Formatting.AQUA));
        return 1;
    }

    private int leave(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (state != State.WAITING && !(state == State.COUNTDOWN && lobbyCountdown)) {
            source.sendError(Text.literal("Počas prebiehajúcej jazdy už nemôžeš opustiť zostavu."));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Participant removed = participants.remove(player.getUuid());
        if (removed == null) {
            player.sendMessage(Text.literal("Nie si prihlásený do ICE RUSH.").formatted(Formatting.YELLOW), false);
            return 0;
        }
        BoatEntity leavingBoat = playerBoats.remove(player.getUuid());
        if (leavingBoat != null && !leavingBoat.isRemoved()) leavingBoat.discard();
        player.stopRiding();
        returnToLobby(player);
        broadcast(source.getServer(), Text.literal(player.getName().getString() + " opustil lobby.").formatted(Formatting.GRAY));
        if (state == State.COUNTDOWN) recheckCountdown(source.getServer());
        return 1;
    }

    private int ready(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Participant participant = participants.get(player.getUuid());
        if (state != State.WAITING || participant == null) {
            player.sendMessage(Text.literal("Najprv sa prihlás do lobby: /icerush join").formatted(Formatting.YELLOW), false);
            return 0;
        }
        if (participant.ready) {
            player.sendMessage(Text.literal("Svoj hlas READY už máš zaznamenaný.").formatted(Formatting.YELLOW), false);
            return 0;
        }
        participant.ready = true;
        broadcast(source.getServer(), Text.literal(player.getName().getString() + " je pripravený.").formatted(Formatting.AQUA));
        tryStartCountdown(source.getServer());
        return 1;
    }

    private int start(ServerCommandSource source) {
        if (arenaWorld == null || startPositions.isEmpty()) {
            source.sendError(Text.literal("Najprv vytvor arénu príkazom /icerush setup."));
            return 0;
        }
        if (state != State.WAITING) {
            source.sendError(Text.literal("Hra už prebieha."));
            return 0;
        }
        if (participants.size() < config.minPlayers) {
            source.sendError(Text.literal("Na štart sú potrební aspoň 2 hráči."));
            return 0;
        }
        if (parkingSpots.size() < participants.size() - 1) {
            source.sendError(Text.literal("Nedostatok parkovacích miest. Potrebných: " + (participants.size() - 1) + ", nastavených: " + parkingSpots.size() + ". Použi /icerush addspot."));
            return 0;
        }
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        int required = (int) Math.ceil(participants.size() * 0.80);
        if (readyCount < required) {
            source.sendError(Text.literal("Pripravených je " + readyCount + "/" + participants.size() + ". Potrebných je aspoň " + required + " (80 %)."));
            return 0;
        }
        beginCountdown(source.getServer());
        return 1;
    }

    private int addSpot(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Parkovacie miesta možno nastavovať iba v lobby."));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        parkingSpots.add(new Vec3d(player.getX(), player.getY(), player.getZ()));
        source.sendFeedback(() -> Text.literal("Parkovacie miesto #" + parkingSpots.size() + " uložené na tvojej pozícii."), false);
        return 1;
    }

    private int setupArena(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (arenaWorld != null || config.arenaConfigured) {
            source.sendError(Text.literal("Aréna už bola vytvorená a jej poloha je uložená v konfigurácii."));
            return 0;
        }
        if (state != State.WAITING) {
            source.sendError(Text.literal("Arénu možno vytvoriť iba v lobby."));
            return 0;
        }
        ServerPlayerEntity admin = source.getPlayerOrThrow();
        arenaWorld = admin.getServerWorld();
        arenaCenter = admin.getBlockPos();
        int cx = arenaCenter.getX();
        int cy = arenaCenter.getY() - 1;
        int cz = arenaCenter.getZ();
        // Jednorazová kruhová dráha: vonkajší priemer 90 blokov, šírka 7 blokov.
        for (int x = -((int) Math.ceil(config.trackOuterRadius)); x <= (int) Math.ceil(config.trackOuterRadius); x++) {
            for (int z = -((int) Math.ceil(config.trackOuterRadius)); z <= (int) Math.ceil(config.trackOuterRadius); z++) {
                double radius = Math.sqrt((double) x * x + (double) z * z);
                if (radius > config.trackOuterRadius) continue;
                for (int y = cy + 1; y <= cy + 6; y++) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, y, cz + z), Blocks.AIR.getDefaultState());
                }
                BlockPos floor = new BlockPos(cx + x, cy, cz + z);
                if (radius >= config.trackInnerRadius) {
                    arenaWorld.setBlockState(floor, Blocks.BLUE_ICE.getDefaultState());
                } else {
                    arenaWorld.setBlockState(floor, Blocks.AIR.getDefaultState());
                }
            }
        }
        // Vyvýšená lobby plošina 80 blokov nad úrovňou dráhy.
        int lobbyY = cy + config.lobbyHeight;
        lobbyPosition = new Vec3d(cx + 0.5, lobbyY + 1.0, cz + 0.5);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY, cz + z), Blocks.SPRUCE_PLANKS.getDefaultState());
                if (Math.abs(x) == 8 || Math.abs(z) == 8) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY + 1, cz + z), Blocks.SPRUCE_FENCE.getDefaultState());
                }
            }
        }
        initializeArenaPositions();
        config.arenaConfigured = true;
        config.arenaDimension = arenaWorld.getRegistryKey().getValue().toString();
        config.arenaX = arenaCenter.getX();
        config.arenaY = arenaCenter.getY();
        config.arenaZ = arenaCenter.getZ();
        config.save();
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(participant.id);
            if (player != null) teleportToLobby(player);
        }
        admin.sendMessage(Text.literal("ICE RUSH aréna vytvorená. Priemer: " + (config.trackOuterRadius * 2.0) + " blokov, šírka približne " + (config.trackOuterRadius - config.trackInnerRadius) + "; lobby: " + config.lobbyHeight + " blokov nad dráhou.").formatted(Formatting.GREEN), false);
        tryStartCountdown(source.getServer());
        return 1;
    }

    void onServerStarted(MinecraftServer server) {
        if (!config.arenaConfigured || config.arenaDimension.isBlank()) return;
        Identifier dimensionId = Identifier.tryParse(config.arenaDimension);
        if (dimensionId == null) {
            IceRushMod.LOGGER.error("Invalid saved ICE RUSH dimension id: {}", config.arenaDimension);
            return;
        }
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
        ServerWorld savedWorld = server.getWorld(worldKey);
        if (savedWorld == null) {
            IceRushMod.LOGGER.error("Saved ICE RUSH arena dimension {} is unavailable", config.arenaDimension);
            return;
        }
        arenaWorld = savedWorld;
        arenaCenter = new BlockPos(config.arenaX, config.arenaY, config.arenaZ);
        lobbyPosition = new Vec3d(config.arenaX + 0.5, config.arenaY + config.arenaLobbyHeight, config.arenaZ + 0.5);
        initializeArenaPositions();
        IceRushMod.LOGGER.info("Restored ICE RUSH arena at {} {} {} in {}", config.arenaX, config.arenaY, config.arenaZ, config.arenaDimension);
    }

    private double arenaInnerRadius() {
        return config.arenaConfigured ? config.arenaInnerRadius : config.trackInnerRadius;
    }

    private double arenaOuterRadius() {
        return config.arenaConfigured ? config.arenaOuterRadius : config.trackOuterRadius;
    }

    private void initializeArenaPositions() {
        int cx = arenaCenter.getX();
        int cy = arenaCenter.getY() - 1;
        int cz = arenaCenter.getZ();
        startPositions.clear();
        parkingSpots.clear();
        crackPositions.clear();
        double trackRadius = (arenaInnerRadius() + arenaOuterRadius()) / 2.0;
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            crackPositions.add(new BlockPos((int) Math.round(cx + Math.cos(angle) * trackRadius), cy, (int) Math.round(cz + Math.sin(angle) * trackRadius)));
        }
        for (int i = 0; i < config.maxPlayers; i++) {
            double angle = (Math.PI * 2.0 * i) / config.maxPlayers;
            Vec3d position = new Vec3d(cx + Math.cos(angle) * trackRadius + 0.5, cy + 1.0, cz + Math.sin(angle) * trackRadius + 0.5);
            startPositions.add(position);
            parkingSpots.add(position);
        }
    }

    private int clearSpots(ServerCommandSource source) {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Parkovacie miesta možno meniť iba v lobby."));
            return 0;
        }
        parkingSpots.clear();
        source.sendFeedback(() -> Text.literal("Všetky parkovacie miesta boli odstránené."), false);
        return 1;
    }

    private int showStats(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        IceRushStatsStore.PlayerStats stats = statsStore.get(player.getUuid());
        player.sendMessage(Text.literal("ICE RUSH ŠTATISTIKY"), false);
        player.sendMessage(Text.literal("Výhry: " + stats.wins + " | Hry: " + stats.gamesPlayed + " | Prehry: " + stats.losses), false);
        player.sendMessage(Text.literal("Body: " + stats.points + " | Turbo bonusy: " + stats.turboBonuses), false);
        player.sendMessage(Text.literal("Rekord rýchlosti: " + String.format(java.util.Locale.ROOT, "%.3f", stats.speedRecord)), false);
        player.sendMessage(Text.literal("Najlepšie parkovanie: " + (stats.bestParkingTicks == 0 ? "—" : (stats.bestParkingTicks / 20.0) + " s")), false);
        return 1;
    }

    private int showTop(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("ICE RUSH — TOP 10 PODĽA BODOV"), false);
        int rank = 1;
        for (Map.Entry<UUID, IceRushStatsStore.PlayerStats> entry : statsStore.top(10)) {
            ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(entry.getKey());
            String name = online == null ? (entry.getValue().playerName.isBlank() ? entry.getKey().toString().substring(0, 8) : entry.getValue().playerName) : online.getName().getString();
            int place = rank++;
            source.sendFeedback(() -> Text.literal(place + ". " + name + " — " + entry.getValue().points + " bodov (výhry: " + entry.getValue().wins + ")"), false);
        }
        return 1;
    }

    private int showRules(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("ICE RUSH — PRAVIDLÁ"), false);
        source.sendFeedback(() -> Text.literal(config.minPlayers + "–" + config.maxPlayers + " hráčov. Po 80 % READY nasleduje odpočet 3–2–1–ŠTART."), false);
        source.sendFeedback(() -> Text.literal("Počas jazdy zostaň v loďke. Rýchlostné zóny, turbo a trhliny menia priebeh kola."), false);
        source.sendFeedback(() -> Text.literal("Jazda trvá " + config.minRideSeconds + "–" + config.maxRideSeconds + " sekúnd. Parkovanie trvá " + config.parkingSeconds + " sekúnd; miest je o jedno menej než aktívnych hráčov."), false);
        source.sendFeedback(() -> Text.literal("Zaparkovanie: +10 bodov, prežitie kola: +5, turbo: +" + config.turboBonusPoints + ", 2. miesto: +60, 3. miesto: +40, výhra: +100."), false);
        source.sendFeedback(() -> Text.literal("Vyhráva posledný zostávajúci hráč. Príkazy: /icerush join, /icerush ready, /icerush stats, /icerush top."), false);
        return 1;
    }

    private int showLastWinner(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Posledný víťaz ICE RUSH: " + statsStore.lastWinner()), false);
        return 1;
    }

    private int showAchievements(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        IceRushStatsStore.PlayerStats stats = statsStore.get(player.getUuid());
        player.sendMessage(Text.literal("ICE RUSH — ACHIEVEMENTY"), false);
        if (stats.achievements.isEmpty()) {
            player.sendMessage(Text.literal("Zatiaľ nemáš odomknuté achievementy."), false);
        } else {
            for (String achievement : stats.achievements) player.sendMessage(Text.literal("• " + achievement), false);
        }
        return 1;
    }

    private int status(ServerCommandSource source) {
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        source.sendFeedback(() -> Text.literal("ICE RUSH | Stav: " + state + " | Kolo: " + round
            + " | Hráči: " + activeCount() + "/" + participants.size()
            + " | Ready: " + readyCount + " | Parkovacie miesta: " + parkingSpots.size()), false);
        return 1;
    }

    private int reset(ServerCommandSource source) {
        resetGame(source.getServer(), true);
        source.sendFeedback(() -> Text.literal("ICE RUSH bolo resetované administrátorom."), true);
        return 1;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        if (state == State.WAITING) {
            sendLobbyMenu(player);
            return;
        }
        Participant existing = participants.get(player.getUuid());
        if (existing == null) lateSpectators.add(player.getUuid());
        phaseBar.addPlayer(player);
        player.changeGameMode(GameMode.SPECTATOR);
        teleportToLobby(player);
        player.sendMessage(Text.literal(existing != null && existing.eliminated
            ? "Už si bol vyradený. Do ďalšej hry zostávaš divákom."
            : "ICE RUSH už prebieha. Sleduješ zápas a do ďalšieho kola sa môžeš zapojiť.").formatted(Formatting.YELLOW), false);
    }

    public void onPlayerDisconnect(UUID playerId) {
        BoatEntity disconnectedBoat = playerBoats.remove(playerId);
        if (disconnectedBoat != null && !disconnectedBoat.isRemoved()) disconnectedBoat.discard();
        ServerBossBar disconnectedBar = speedBars.remove(playerId);
        if (disconnectedBar != null) disconnectedBar.clearPlayers();
        if (state == State.WAITING || (state == State.COUNTDOWN && lobbyCountdown)) {
            participants.remove(playerId);
            if (state == State.COUNTDOWN) countdownNeedsValidation = true;
        } else {
            Participant participant = participants.get(playerId);
            if (participant != null) participant.eliminated = true;
            lateSpectators.remove(playerId);
        }
    }

    private void tryStartCountdown(MinecraftServer server) {
        if (state != State.WAITING || participants.size() < config.minPlayers || arenaWorld == null) return;
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        int required = (int) Math.ceil(participants.size() * 0.80);
        if (readyCount >= required && parkingSpots.size() >= participants.size() - 1) beginCountdown(server);
    }

    private void recheckCountdown(MinecraftServer server) {
        if (state != State.COUNTDOWN) return;
        int players = participants.size();
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        int required = (int) Math.ceil(players * 0.80);
        if (players < config.minPlayers || readyCount < required || parkingSpots.size() < players - 1) {
            state = State.WAITING;
            timer = 0;
            boatsLocked = false;
            lobbyCountdown = false;
            for (BoatEntity boat : playerBoats.values()) if (!boat.isRemoved()) boat.discard();
            playerBoats.clear();
            for (Participant participant : participants.values()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
                if (player != null) returnToLobby(player);
            }
            phaseBar.clearPlayers();
            broadcast(server, Text.literal("Odpočet zrušený — podmienka 80 % READY alebo počet hráčov už nie je splnený.").formatted(Formatting.RED));
        }
    }

    private void beginCountdown(MinecraftServer server) {
        startCountdown(server, true);
    }

    private void beginNextRoundCountdown(MinecraftServer server) {
        startCountdown(server, false);
    }

    private void startCountdown(MinecraftServer server, boolean forLobby) {
        spawnRoundBoats(server);
        boatsLocked = true;
        lobbyCountdown = forLobby;
        state = State.COUNTDOWN;
        timer = config.countdownSeconds * 20;
        phaseBar.setName(Text.literal(forLobby ? "ŠTARTOVÝ ODPOČET" : "PRÍPRAVA NA KOLO " + (round + 1)));
        phaseBar.setColor(BossBar.Color.YELLOW);
        phaseBar.setPercent(1.0F);
        addPhaseBarPlayers(server);
        if (forLobby) broadcast(server, Text.literal("ICE RUSH sa začína!").formatted(Formatting.GOLD, Formatting.BOLD));
        else broadcast(server, Text.literal("Priprav sa na ďalšie kolo!").formatted(Formatting.GOLD, Formatting.BOLD));
        broadcast(server, Text.literal(String.valueOf(config.countdownSeconds)).formatted(Formatting.YELLOW, Formatting.BOLD));
    }

    public void tick(MinecraftServer server) {
        if (state == State.WAITING) return;
        if (state == State.COUNTDOWN) {
            if (arenaWorld == null) {
                state = State.WAITING;
                timer = 0;
                broadcast(server, Text.literal("Odpočet zrušený — aréna nie je nastavená.").formatted(Formatting.RED));
                return;
            }
            if (lobbyCountdown && countdownNeedsValidation) {
                countdownNeedsValidation = false;
                recheckCountdown(server);
                if (state != State.COUNTDOWN) return;
            }
            enforceBoatSeats(server);
            if (boatsLocked) {
                for (BoatEntity boat : playerBoats.values()) {
                    if (!boat.isRemoved()) boat.setVelocity(Vec3d.ZERO);
                }
            }
            timer--;
            if (timer > 0 && timer % 20 == 0) {
                int secondsLeft = timer / 20;
                if (secondsLeft < config.countdownSeconds) {
                    broadcast(server, Text.literal(String.valueOf(secondsLeft)).formatted(Formatting.YELLOW, Formatting.BOLD));
                }
            }
            if (timer <= 0) startRound(server);
            return;
        }
        if (state == State.RACING) {
            if (server.getTicks() % 20 == 0) updateHud(server);
            enforceBoatSeats(server);
            roundTicks++;
            updateSpeedBars(server);
            tickTurbo(server);
            tickCracks(server);
            applyRoundEvent(server);
            if (activeCount() <= 1) {
                declareWinner(server);
                return;
            }
            timer--;
            if (timer <= 0) beginParking(server);
            return;
        }
        if (state == State.PARKING) {
            phaseBar.setPercent(Math.max(0.0F, Math.min(1.0F, timer / (float) (config.parkingSeconds * 20))));
            if (server.getTicks() % 20 == 0) updateHud(server);
            enforceBoatSeats(server);
            tickCracks(server);
            if (activeCount() <= 1) {
                declareWinner(server);
                return;
            }
            tickParkingVisuals(server);
            updateParking(server);
            timer--;
            int requiredSpots = Math.max(0, activeCount() - 1);
            if (occupiedSpots.size() >= requiredSpots || timer <= 0) finishParking(server);
            return;
        }
        if (state == State.FINISHED) {
            timer--;
            if (timer <= 0) resetGame(server, false);
        }
    }

    private void startRound(MinecraftServer server) {
        if (!lobbyCountdown) admitLateSpectators(server);
        if (round == 0) resultsCommitted = false;
        boatsLocked = false;
        lobbyCountdown = false;
        round++;
        for (Participant p : participants.values()) {
            p.parked = false;
            p.parkingSpot = -1;
            p.ready = false;
        }
        occupiedSpots.clear();
        state = State.RACING;
        timer = ThreadLocalRandom.current().nextInt(config.minRideSeconds * 20, config.maxRideSeconds * 20 + 1);
        spawnRoundBoats(server);
        setupSpeedBars(server);
        phaseBar.setName(Text.literal("JAZDA — KOLO " + round));
        phaseBar.setColor(BossBar.Color.BLUE);
        phaseBar.setPercent(1.0F);
        addPhaseBarPlayers(server);
        roundTicks = 0;
        prepareRoundModifiers(server);
        broadcast(server, Text.literal("ŠTART! Kolo " + round + " — jazda!").formatted(Formatting.GREEN, Formatting.BOLD));
    }

    private void beginParking(MinecraftServer server) {
        clearSpeedBars();
        phaseBar.setName(Text.literal("PARKOVANIE — 20 SEKÚND"));
        phaseBar.setColor(BossBar.Color.YELLOW);
        phaseBar.setPercent(1.0F);
        Collections.shuffle(parkingSpots);
        showParkingPads();
        state = State.PARKING;
        timer = config.parkingSeconds * 20;
        occupiedSpots.clear();
        for (Participant p : participants.values()) { p.parked = false; p.parkingSpot = -1; }
        broadcast(server, Text.literal("PARKOVANIE! Máš 20 sekúnd na obsadenie miesta.").formatted(Formatting.GOLD, Formatting.BOLD));
    }

    private void showParkingPads() {
        hideParkingPads();
        if (arenaWorld == null || arenaCenter == null) return;
        int count = Math.min(parkingSpots.size(), Math.max(0, activeCount() - 1));
        for (int i = 0; i < count; i++) {
            Vec3d spot = parkingSpots.get(i);
            BlockPos center = new BlockPos((int) Math.floor(spot.x), arenaCenter.getY() - 1, (int) Math.floor(spot.z));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = center.add(dx, 0, dz);
                    parkingOriginalStates.putIfAbsent(pos, arenaWorld.getBlockState(pos));
                    arenaWorld.setBlockState(pos, Blocks.LIGHT_BLUE_CONCRETE.getDefaultState());
                }
            }
        }
    }

    private void hideParkingPads() {
        if (arenaWorld != null) {
            for (Map.Entry<BlockPos, BlockState> entry : parkingOriginalStates.entrySet()) {
                arenaWorld.setBlockState(entry.getKey(), entry.getValue());
            }
        }
        parkingOriginalStates.clear();
    }

    private void tickParkingVisuals(MinecraftServer server) {
        if (arenaWorld == null || server.getTicks() % 10 != 0) return;
        for (BlockPos pos : parkingOriginalStates.keySet()) {
            arenaWorld.spawnParticles(ParticleTypes.SNOWFLAKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 2, 0.25, 0.1, 0.25, 0.005);
        }
    }

    private void updateParking(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.parked) {
                if (participant.parkingSpot >= 0) {
                    occupiedSpots.add(participant.parkingSpot);
                    BoatEntity parkedBoat = playerBoats.get(participant.id);
                    if (parkedBoat != null && !parkedBoat.isRemoved()) {
                        Vec3d lockedPosition = parkingSpots.get(participant.parkingSpot);
                        parkedBoat.setPosition(lockedPosition);
                        parkedBoat.setVelocity(Vec3d.ZERO);
                    }
                }
                continue;
            }
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof BoatEntity)) continue;
            Vec3d boatPosition = vehicle.getPos();
            for (int i = 0; i < Math.min(parkingSpots.size(), Math.max(0, activeCount() - 1)); i++) {
                if (occupiedSpots.contains(i)) continue;
                if (boatPosition.squaredDistanceTo(parkingSpots.get(i)) <= config.parkingRadius * config.parkingRadius) {
                    participant.parked = true;
                    participant.parkingSpot = i;
                    participant.parkingTimeTicks = config.parkingSeconds * 20 - timer;
                    occupiedSpots.add(i);
                    player.sendMessage(Text.literal("Parkovacie miesto obsadené!").formatted(Formatting.GREEN), true);
                    break;
                }
            }
        }
    }

    private void finishParking(MinecraftServer server) {
        hideParkingPads();
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (participant.parked) {
                participant.points += 10;
                participant.points += 5;
                if (participant.parkingTimeTicks > 0 &&
                    (participant.bestParkingTicks == 0 || participant.parkingTimeTicks < participant.bestParkingTicks)) {
                    participant.bestParkingTicks = participant.parkingTimeTicks;
                }
                if (player != null) player.sendMessage(Text.literal("+10 parkovanie +5 prežitie kola. Spolu: " + participant.points).formatted(Formatting.GREEN), false);
            } else {
                int placeBeforeElimination = activeCount();
                if (placeBeforeElimination == 2) participant.points += 60;
                else if (placeBeforeElimination == 3) participant.points += 40;
                participant.eliminated = true;
                if (player != null) {
                    player.stopRiding();
                    BoatEntity eliminatedBoat = playerBoats.remove(participant.id);
                    if (eliminatedBoat != null && !eliminatedBoat.isRemoved()) eliminatedBoat.discard();
                    ServerBossBar speedBar = speedBars.remove(participant.id);
                    if (speedBar != null) speedBar.clearPlayers();
                    phaseBar.removePlayer(player);
                    if (arenaWorld != null) {
                        arenaWorld.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(), 24, 0.5, 0.4, 0.5, 0.03);
                        arenaWorld.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0F, 0.7F);
                    }
                    player.changeGameMode(GameMode.SPECTATOR);
                    player.sendMessage(Text.literal("VYPADÁVAŠ! Nenašiel/nenašla si parkovacie miesto. Body: " + participant.points).formatted(Formatting.RED, Formatting.BOLD), false);
                }
            }
        }

        if (activeCount() <= 1) {
            declareWinner(server);
            return;
        }

        for (Participant participant : participants.values()) {
            participant.parked = false;
            participant.parkingSpot = -1;
            participant.parkingTimeTicks = 0;
        }
        admitLateSpectators(server);
        beginNextRoundCountdown(server);
    }

    private void addPhaseBarPlayers(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) phaseBar.addPlayer(player);
        }
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) phaseBar.addPlayer(player);
        }
    }

    private void updateHud(MinecraftServer server) {
        String phase = state == State.PARKING ? "PARKOVANIE" : "JAZDA";
        Text hud = Text.literal("ICE RUSH  |  Hráči: " + activeCount() + "  |  Kolo: " + round + "  |  " + phase);
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null && !participant.eliminated) player.sendMessage(hud, true);
        }
    }

    private void setupSpeedBars(MinecraftServer server) {
        clearSpeedBars();
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            ServerBossBar bar = new ServerBossBar(Text.literal("RÝCHLOSŤ — OCHRANA ŠTARTU"), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
            bar.setPercent(0.5F);
            bar.addPlayer(player);
            speedBars.put(participant.id, bar);
        }
    }

    private void clearSpeedBars() {
        for (ServerBossBar bar : speedBars.values()) bar.clearPlayers();
        speedBars.clear();
    }

    private void updateSpeedBars(MinecraftServer server) {
        if (roundTicks < config.speedGraceSeconds * 20 || server.getTicks() % 5 != 0) return; // prvých 5 sekúnd sa rýchlosť nevyhodnocuje
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)) continue;
            double speed = boat.getVelocity().horizontalLength();
            participant.speedRecord = Math.max(participant.speedRecord, speed);
            double normalizedSpeed = Math.max(0.0, Math.min(1.0, speed / config.speedReference));
            int availableSteps = (100 - config.minimumSpeedPercent) / 5;
            int speedTier = (int) Math.floor(normalizedSpeed * availableSteps);
            participant.speedPercent = Math.max(config.minimumSpeedPercent,
                Math.min(100, config.minimumSpeedPercent + speedTier * 5));
            ServerBossBar bar = speedBars.get(participant.id);
            if (bar == null) continue;
            if (participant.speedPercent >= 80) {
                bar.setColor(BossBar.Color.GREEN);
                bar.setName(Text.literal("RÝCHLOSŤ — " + participant.speedPercent + "%"));
            } else if (participant.speedPercent >= 70) {
                bar.setColor(BossBar.Color.YELLOW);
                bar.setName(Text.literal("RÝCHLOSŤ — " + participant.speedPercent + "%"));
            } else {
                bar.setColor(BossBar.Color.RED);
                bar.setName(Text.literal("RÝCHLOSŤ — " + participant.speedPercent + "%"));
            }
            bar.setPercent(participant.speedPercent / 100.0F);
        }
    }

    private void prepareRoundModifiers(MinecraftServer server) {
        resetCracks();
        turboZones.clear();
        for (Participant participant : participants.values()) {
            participant.turboTicks = 0;
            participant.turboActive = false;
            participant.speedPercent = config.minimumSpeedPercent;
            participant.turboZonesUsed.clear();
        }
        roundEvent = ThreadLocalRandom.current().nextInt(100) < config.eventChancePercent
            ? List.of("SNOW", "WIND", "SUPER_ICE", "TURBO_MADNESS", "FOG").get(ThreadLocalRandom.current().nextInt(5))
            : "NONE";
        int zoneCount = config.turboZones * (roundEvent.equals("TURBO_MADNESS") ? 2 : 1);
        for (int i = 0; i < zoneCount; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            turboZones.add(new Vec3d(arenaCenter.getX() + Math.cos(angle) * ((arenaInnerRadius() + arenaOuterRadius()) / 2.0) + 0.5, arenaCenter.getY(), arenaCenter.getZ() + Math.sin(angle) * ((arenaInnerRadius() + arenaOuterRadius()) / 2.0) + 0.5));
        }
        if (!roundEvent.equals("NONE")) {
            String eventName = switch (roundEvent) {
                case "SNOW" -> "SNEŽENIE";
                case "WIND" -> "VIETOR";
                case "SUPER_ICE" -> "SUPER KLZKÝ ĽAD";
                case "TURBO_MADNESS" -> "TURBO ŠIALENSTVO";
                case "FOG" -> "HMLA";
                default -> roundEvent;
            };
            broadcast(server, Text.literal("MIMORIADNA UDALOSŤ: " + eventName).formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        }
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < crackPositions.size(); i++) {
            if (ThreadLocalRandom.current().nextInt(100) >= config.crackChancePercent) continue;
            int crackIndex = i;
            boolean adjacent = selected.stream().anyMatch(n -> Math.abs(n - crackIndex) == 1 || Math.abs(n - crackIndex) == 7);
            if (adjacent) continue;
            selected.add(i);
            BlockPos pos = crackPositions.get(i);
            warningCracks.put(pos, config.crackWarningSeconds * 20);
            arenaWorld.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 12, 0.35, 0.15, 0.35, 0.02);
            arenaWorld.playSound(null, pos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.8F, 0.7F);
        }
    }

    private void resetCracks() {
        if (arenaWorld != null) {
            for (BlockPos pos : openCracks) arenaWorld.setBlockState(pos, Blocks.BLUE_ICE.getDefaultState());
            for (BlockPos pos : warningCracks.keySet()) arenaWorld.setBlockState(pos, Blocks.BLUE_ICE.getDefaultState());
        }
        openCracks.clear();
        warningCracks.clear();
    }

    private void tickCracks(MinecraftServer server) {
        if (arenaWorld == null || (warningCracks.isEmpty() && openCracks.isEmpty())) return;
        for (Map.Entry<BlockPos, Integer> entry : new ArrayList<>(warningCracks.entrySet())) {
            BlockPos pos = entry.getKey();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                arenaWorld.setBlockState(pos, Blocks.AIR.getDefaultState());
                openCracks.add(pos);
                warningCracks.remove(pos);
                arenaWorld.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 18, 0.4, 0.1, 0.4, 0.02);
            } else {
                warningCracks.put(pos, left);
                if (left % 10 == 0) {
                    arenaWorld.setBlockState(pos, left % 20 == 0 ? Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState() : Blocks.BLUE_ICE.getDefaultState());
                    arenaWorld.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 5, 0.25, 0.1, 0.25, 0.01);
                }
            }
        }
        if (openCracks.isEmpty()) return;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null && player.getVehicle() instanceof BoatEntity boat && boat.getY() < arenaCenter.getY() - 2) {
                int placeBeforeElimination = activeCount();
                if (placeBeforeElimination == 2) participant.points += 60;
                else if (placeBeforeElimination == 3) participant.points += 40;
                participant.eliminated = true;
                player.stopRiding();
                BoatEntity eliminatedBoat = playerBoats.remove(participant.id);
                if (eliminatedBoat != null && !eliminatedBoat.isRemoved()) eliminatedBoat.discard();
                ServerBossBar speedBar = speedBars.remove(participant.id);
                if (speedBar != null) speedBar.clearPlayers();
                phaseBar.removePlayer(player);
                if (arenaWorld != null) {
                    arenaWorld.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(), 24, 0.5, 0.4, 0.5, 0.03);
                    arenaWorld.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0F, 0.7F);
                }
                player.changeGameMode(GameMode.SPECTATOR);
                player.sendMessage(Text.literal("VYPADÁVAŠ! Prepadol si sa cez prasknutý ľad. Body: " + participant.points).formatted(Formatting.RED, Formatting.BOLD), false);
            }
        }
    }

    private void tickTurbo(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)) continue;
            if (participant.turboTicks > 0) {
                participant.turboTicks--;
                if (participant.turboTicks == 0 && participant.turboActive) {
                    boat.setVelocity(boat.getVelocity().multiply(1.0 / 1.05));
                    participant.turboActive = false;
                }
            }
            if (roundTicks < config.speedGraceSeconds * 20 || server.getTicks() % 5 != 0) continue;
            Vec3d velocity = boat.getVelocity();
            for (int i = 0; i < turboZones.size(); i++) {
                if (boat.getPos().squaredDistanceTo(turboZones.get(i)) > 9.0) {
                    participant.turboZonesUsed.remove(i);
                    continue;
                }
                if (participant.turboZonesUsed.contains(i)) continue;
                if (participant.speedPercent < 80) continue;
                participant.turboZonesUsed.add(i);
                if (!participant.turboActive) {
                    boat.setVelocity(boat.getVelocity().multiply(1.05));
                    participant.turboActive = true;
                }
                participant.turboTicks = config.turboDurationSeconds * 20;
                participant.turboBonuses++;
                participant.points += config.turboBonusPoints;
                arenaWorld.spawnParticles(ParticleTypes.FIREWORK, boat.getX(), boat.getY() + 0.5, boat.getZ(), 18, 0.5, 0.3, 0.5, 0.08);
                arenaWorld.playSound(null, boat.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0F, 1.4F);
                player.sendMessage(Text.literal("TURBO! +" + config.turboBonusPoints + " body").formatted(Formatting.GREEN, Formatting.BOLD), true);
                break;
            }
        }
    }

    private void applyRoundEvent(MinecraftServer server) {
        if (roundEvent.equals("NONE") || arenaWorld == null || server.getTicks() % 5 != 0) return;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)) continue;
            if (roundEvent.equals("SNOW") && server.getTicks() % 10 == 0) {
                arenaWorld.spawnParticles(ParticleTypes.SNOWFLAKE, boat.getX(), boat.getY() + 1.0, boat.getZ(), 8, 0.7, 0.3, 0.7, 0.01);
            } else if (roundEvent.equals("WIND")) {
                double yaw = Math.toRadians(boat.getYaw());
                boat.addVelocity(-Math.sin(yaw) * 0.00025, 0.0, Math.cos(yaw) * 0.00025);
            } else if (roundEvent.equals("SUPER_ICE")) {
                boat.setVelocity(boat.getVelocity().multiply(1.0025));
            } else if (roundEvent.equals("TURBO_MADNESS") && participant.turboTicks > 0) {
                boat.setVelocity(boat.getVelocity().multiply(1.004));
            } else if (roundEvent.equals("FOG") && server.getTicks() % 20 == 0) {
                arenaWorld.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, boat.getX(), boat.getY() + 0.4, boat.getZ(), 5, 1.0, 0.2, 1.0, 0.01);
            }
        }
    }

    private void admitLateSpectators(MinecraftServer server) {
        if (lateSpectators.isEmpty() || participants.size() >= config.maxPlayers) return;
        for (UUID id : new ArrayList<>(lateSpectators)) {
            if (participants.size() >= config.maxPlayers) break;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null) continue;
            Participant participant = new Participant(id);
            participant.playerName = player.getName().getString();
            participants.put(id, participant);
            lateSpectators.remove(id);
            player.changeGameMode(GameMode.SURVIVAL);
            player.sendMessage(Text.literal("Pripojil si sa do ďalšieho kola ICE RUSH!").formatted(Formatting.GREEN), false);
        }
    }

    private void spawnRoundBoats(MinecraftServer server) {
        if (arenaWorld == null || startPositions.isEmpty()) return;
        Collections.shuffle(startPositions);
        int index = 0;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            Vec3d pos = startPositions.get(index++ % startPositions.size());
            BoatEntity boat = playerBoats.get(participant.id);
            if (boat == null || boat.isRemoved()) {
                boat = new BoatEntity(EntityType.BOAT, arenaWorld);
                boat.setPosition(pos);
                arenaWorld.spawnEntity(boat);
                playerBoats.put(participant.id, boat);
            } else {
                boat.setPosition(pos);
                boat.setVelocity(Vec3d.ZERO);
            }
            player.teleport(arenaWorld, pos.x, pos.y + 1.0, pos.z, player.getYaw(), 0.0F);
            player.startRiding(boat, true);
        }
    }

    private void enforceBoatSeats(MinecraftServer server) {
        if (arenaWorld == null) return;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            BoatEntity boat = playerBoats.get(participant.id);
            if (boat == null || boat.isRemoved()) {
                boat = new BoatEntity(EntityType.BOAT, arenaWorld);
                boat.setPosition(player.getX(), player.getY() - 0.5, player.getZ());
                arenaWorld.spawnEntity(boat);
                playerBoats.put(participant.id, boat);
            }
            if (player.getVehicle() != boat) player.startRiding(boat, true);
        }
    }

    private void declareWinner(MinecraftServer server) {
        clearSpeedBars();
        phaseBar.clearPlayers();
        winner = participants.values().stream().filter(p -> !p.eliminated).map(p -> p.id).findFirst().orElse(null);
        state = State.FINISHED;
        timer = config.finishHoldSeconds * 20;
        if (winner != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(winner);
            String name = player == null ? "Víťaz" : player.getName().getString();
            broadcast(server, Text.literal("🏆 " + name + " VYHRÁVA ICE RUSH!").formatted(Formatting.GOLD, Formatting.BOLD));
            Participant winnerParticipant = participants.get(winner);
            if (winnerParticipant != null) winnerParticipant.points += 100;
            if (player != null) {
                if (arenaWorld != null) {
                    arenaWorld.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 1.0, player.getZ(), 100, 1.2, 1.0, 1.2, 0.12);
                    arenaWorld.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5F, 0.8F);
                }
                player.sendMessage(Text.literal("Gratulujeme! Si posledný preživší. +100 bodov. Spolu: " + (winnerParticipant == null ? 100 : winnerParticipant.points)).formatted(Formatting.GREEN, Formatting.BOLD), false);
            }
        } else {
            broadcast(server, Text.literal("ICE RUSH sa skončilo bez víťaza.").formatted(Formatting.RED));
        }
        commitResults(server);
    }

    private void commitResults(MinecraftServer server) {
        if (resultsCommitted) return;
        resultsCommitted = true;
        for (Participant participant : participants.values()) {
            IceRushStatsStore.PlayerStats stats = statsStore.get(participant.id);
            stats.gamesPlayed++;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) stats.playerName = player.getName().getString();
            else if (!participant.playerName.isBlank()) stats.playerName = participant.playerName;
            stats.points += participant.points;
            stats.turboBonuses += participant.turboBonuses;
            stats.speedRecord = Math.max(stats.speedRecord, participant.speedRecord);
            if (participant.bestParkingTicks > 0 &&
                (stats.bestParkingTicks == 0 || participant.bestParkingTicks < stats.bestParkingTicks)) {
                stats.bestParkingTicks = participant.bestParkingTicks;
            }
            if (participant.id.equals(winner)) {
                stats.wins++;
                if (!stats.achievements.contains("First Win")) stats.achievements.add("First Win");
                ServerPlayerEntity winnerPlayer = server.getPlayerManager().getPlayer(winner);
                String winnerName = winnerPlayer == null ? (stats.playerName.isBlank() ? winner.toString().substring(0, 8) : stats.playerName) : winnerPlayer.getName().getString();
                statsStore.setLastWinner(winnerName);
            } else stats.losses++;
        }
        statsStore.save();
    }

    private void resetGame(MinecraftServer server, boolean adminReset) {
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) returnToLobby(player);
        }
        participants.clear();
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) returnToLobby(player);
        }
        lateSpectators.clear();
        occupiedSpots.clear();
        clearSpeedBars();
        phaseBar.clearPlayers();
        hideParkingPads();
        resetCracks();
        for (BoatEntity boat : playerBoats.values()) if (!boat.isRemoved()) boat.discard();
        playerBoats.clear();
        state = State.WAITING;
        timer = 0;
        countdownNeedsValidation = false;
        boatsLocked = false;
        round = 0;
        resultsCommitted = false;
        winner = null;
        if (!adminReset) broadcast(server, Text.literal("ICE RUSH sa skončilo. Lobby je znovu otvorené.").formatted(Formatting.AQUA));
    }

    private void teleportToLobby(ServerPlayerEntity player) {
        if (arenaWorld == null || lobbyPosition == null) return;
        player.teleport(arenaWorld, lobbyPosition.x, lobbyPosition.y, lobbyPosition.z, player.getYaw(), 0.0F);
    }

    private void returnToLobby(ServerPlayerEntity player) {
        player.stopRiding();
        teleportToLobby(player);
        player.changeGameMode(GameMode.ADVENTURE);
        sendLobbyMenu(player);
    }

    private void sendLobbyMenu(ServerPlayerEntity player) {
        MutableText menu = Text.literal("ICE RUSH  |  ");
        menu.append(Text.literal("[JOIN]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush join"))));
        menu.append(Text.literal("  [READY]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush ready"))));
        menu.append(Text.literal("  [PRAVIDLÁ]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush rules"))));
        menu.append(Text.literal("  [TOP 10]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush top"))));
        menu.append(Text.literal("  [ŠTATISTIKY]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush stats"))));
        menu.append(Text.literal("  [POSLEDNÝ VÍŤAZ]").styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/icerush lastwinner"))));
        player.sendMessage(menu, false);
    }

    private int activeCount() {
        return (int) participants.values().stream().filter(p -> !p.eliminated).count();
    }

    private void broadcast(MinecraftServer server, Text message) {
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) player.sendMessage(message, false);
        }
    }
}
