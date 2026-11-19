package sk.toso.icerush;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.DyeableItem;
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
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.util.Formatting;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private final List<BlockPos> parkingAnimationBlocks = new ArrayList<>();
    private int parkingAnimationIndex;
    private int visibleParkingPadCount = -1;
    private int celebrationTicks;
    private boolean sidebarInitialized;
    private boolean lobbyBoardBuilt;
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
        private boolean parkingRewarded;
        private boolean survivalRewarded;
        private int parkingSpot = -1;
        private int points;
        private int turboTicks;
        private boolean turboActive;
        private int turboBonuses;
        private int parkingTimeTicks;
        private int bestParkingTicks;
        private int roundsSurvived;
        private double appliedSpeedScale = 1.0;
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
            .then(CommandManager.literal("defaultspots").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> defaultSpots(ctx.getSource())))
            .then(CommandManager.literal("setup").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> setupArena(ctx.getSource())))
            .then(CommandManager.literal("lobbysetup").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> setupLobbyControls(ctx.getSource())))
            .then(CommandManager.literal("repair").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> repairArena(ctx.getSource())))
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
            player.interactionManager.changeGameMode(GameMode.SPECTATOR);
            teleportToLobby(player);
            player.sendMessage(Text.literal("Hra už prebieha. Do konca zápasu zostávaš divákom.").formatted(Formatting.YELLOW), false);
            player.sendMessage(Text.literal("Tvoj režim: " + (player.isSpectator() ? "DIVÁK" : "HRÁČ")), false);
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
        player.interactionManager.changeGameMode(GameMode.ADVENTURE);
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
            player.sendMessage(Text.literal("Tvoj režim: " + (player.isSpectator() ? "DIVÁK" : "HRÁČ")), false);
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

    private int addSpot(ServerCommandSource source) {
        source.sendError(Text.literal("Parkoviská sa generujú automaticky v strede arény: vždy presne počet aktívnych hráčov mínus jedno. Ručné pridávanie nie je povolené."));
        return 0;
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
        for (int x = -((int) Math.ceil(config.trackOuterRadius + 5)); x <= (int) Math.ceil(config.trackOuterRadius + 5); x++) {
            for (int z = -((int) Math.ceil(config.trackOuterRadius + 5)); z <= (int) Math.ceil(config.trackOuterRadius + 5); z++) {
                double radius = Math.sqrt((double) x * x + (double) z * z);
                if (radius > config.trackOuterRadius + 5) continue;
                for (int y = cy + 1; y <= cy + 6; y++) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, y, cz + z), Blocks.AIR.getDefaultState());
                }
                // Blue-ice driving ring; the center is open except during parking.
                BlockPos floor = new BlockPos(cx + x, cy, cz + z);
                // The inner disk stays empty outside the parking phase.
                arenaWorld.setBlockState(floor, radius < config.trackInnerRadius
                    ? Blocks.AIR.getDefaultState()
                    : radius > config.trackOuterRadius
                        ? Blocks.PACKED_ICE.getDefaultState()
                        : Blocks.BLUE_ICE.getDefaultState());
            }
        }
        // Vyvýšená lobby plošina 80 blokov nad úrovňou dráhy.
        int lobbyY = cy + config.lobbyHeight;
        lobbyPosition = new Vec3d(cx + 0.5, lobbyY + 1.0, cz + 0.5);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                BlockState lobbyFloor = Math.abs(x) <= 5 && Math.abs(z) <= 5
                    ? Blocks.GLASS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY, cz + z), lobbyFloor);
                if (Math.abs(x) == 8 || Math.abs(z) == 8) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY + 1, cz + z), Blocks.SPRUCE_FENCE.getDefaultState());
                }
                if (Math.abs(x) == 7 && Math.abs(z) == 7) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY + 1, cz + z), Blocks.SEA_LANTERN.getDefaultState());
                    arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY + 2, cz + z), Blocks.BLUE_ICE.getDefaultState());
                }
            }
        }
        config.arenaInnerRadius = config.trackInnerRadius;
        config.arenaOuterRadius = config.trackOuterRadius;
        config.arenaLobbyHeight = config.lobbyHeight;
        initializeArenaPositions();
        buildLobbyControls();
        config.arenaConfigured = true;
        config.arenaDimension = arenaWorld.getRegistryKey().getValue().toString();
        config.arenaX = arenaCenter.getX();
        config.arenaY = arenaCenter.getY();
        config.arenaZ = arenaCenter.getZ();
        config.save();
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            player.interactionManager.changeGameMode(GameMode.ADVENTURE);
            teleportToLobby(player);
            sendLobbyMenu(player);
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
        buildLobbyControls();
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
            double angle = (Math.PI * 2.0 * i) / 8.0 + Math.PI / 16.0;
            crackPositions.add(new BlockPos((int) Math.round(cx + Math.cos(angle) * trackRadius), cy, (int) Math.round(cz + Math.sin(angle) * trackRadius)));
        }
        for (int i = 0; i < config.maxPlayers; i++) {
            double angle = (Math.PI * 2.0 * i) / config.maxPlayers;
            Vec3d position = new Vec3d(cx + Math.cos(angle) * trackRadius + 0.5, cy + 1.0, cz + Math.sin(angle) * trackRadius + 0.5);
            startPositions.add(position);
        }
        // Central 4x4 grid: every parking space is a 3x3 platform with a one-block gap.
        List<Vec3d> centralGrid = new ArrayList<>();
        for (int gx : new int[]{-6, -2, 2, 6}) {
            for (int gz : new int[]{-6, -2, 2, 6}) {
                centralGrid.add(new Vec3d(cx + gx + 0.5, cy + 1.0, cz + gz + 0.5));
            }
        }
        centralGrid.sort((a, b) -> Double.compare(
            Math.pow(a.x - (cx + 0.5), 2) + Math.pow(a.z - (cz + 0.5), 2),
            Math.pow(b.x - (cx + 0.5), 2) + Math.pow(b.z - (cz + 0.5), 2)));
        parkingSpots.addAll(centralGrid.subList(0, Math.min(centralGrid.size(), Math.max(0, config.maxPlayers - 1))));
    }

    private int repairArena(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Opravu arény môžeš spustiť iba v lobby."));
            return 0;
        }
        source.getPlayerOrThrow();
        if (arenaWorld == null || arenaCenter == null) {
            if (!config.arenaConfigured || config.arenaDimension.isBlank()) {
                source.sendError(Text.literal("Aréna ešte nie je nastavená. Použi /icerush setup."));
                return 0;
            }
            Identifier dimension = Identifier.tryParse(config.arenaDimension);
            ServerWorld restored = dimension == null ? null : source.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, dimension));
            if (restored == null) {
                source.sendError(Text.literal("Nepodarilo sa obnoviť svet uloženej arény."));
                return 0;
            }
            arenaWorld = restored;
            arenaCenter = new BlockPos(config.arenaX, config.arenaY, config.arenaZ);
            lobbyPosition = new Vec3d(config.arenaX + 0.5, config.arenaY + config.arenaLobbyHeight, config.arenaZ + 0.5);
        }
        int cx = arenaCenter.getX(), cy = arenaCenter.getY() - 1, cz = arenaCenter.getZ();
        for (int x = -(int)Math.ceil(arenaOuterRadius() + 5); x <= (int)Math.ceil(arenaOuterRadius() + 5); x++) {
            for (int z = -(int)Math.ceil(arenaOuterRadius() + 5); z <= (int)Math.ceil(arenaOuterRadius() + 5); z++) {
                double radius = Math.sqrt((double)x * x + (double)z * z);
                if (radius > arenaOuterRadius() + 5) continue;
                for (int y = cy + 1; y <= cy + 7; y++) {
                    arenaWorld.setBlockState(new BlockPos(cx + x, y, cz + z), Blocks.AIR.getDefaultState());
                }
                arenaWorld.setBlockState(new BlockPos(cx + x, cy, cz + z),
                    radius < arenaInnerRadius()
                        ? Blocks.AIR.getDefaultState()
                        : radius > arenaOuterRadius()
                            ? Blocks.PACKED_ICE.getDefaultState()
                            : Blocks.BLUE_ICE.getDefaultState());
            }
        }
        initializeArenaPositions();
        buildLobbyControls();
        lobbyBoardBuilt = false;
        updateLobbyLeaderboard(source.getServer());
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player.getServerWorld() == arenaWorld) {
                player.interactionManager.changeGameMode(GameMode.ADVENTURE);
                teleportToLobby(player);
                sendLobbyMenu(player);
            }
        }
        source.sendFeedback(() -> Text.literal("ToSo IceRush: ľadová plocha, stred, parkoviská a lobby boli obnovené.").formatted(Formatting.GREEN, Formatting.BOLD), true);
        return 1;
    }

    private int setupLobbyControls(ServerCommandSource source) {
        if (arenaWorld == null || lobbyPosition == null) {
            source.sendError(Text.literal("Najprv vytvor alebo obnov arénu príkazom /icerush setup."));
            return 0;
        }
        int before = arenaWorld.getEntitiesByClass(Entity.class,
            new Box(arenaCenter.getX() - 10, lobbyPosition.y - 2, arenaCenter.getZ() - 2,
                    arenaCenter.getX() + 10, lobbyPosition.y + 5, arenaCenter.getZ() + 10),
            entity -> entity.getCommandTags().contains("icerush_button_join")).size();
        buildLobbyControls();
        lobbyBoardBuilt = false;
        updateLobbyLeaderboard(source.getServer());
        source.sendFeedback(() -> Text.literal(before > 0 ? "Lobby ovládanie a výsledková tabuľa boli skontrolované." : "Lobby ovládacie panely a výsledková tabuľa boli vytvorené."), true);
        return 1;
    }

    private void buildLobbyControls() {
        if (arenaWorld == null || arenaCenter == null) return;
        int lobbyY = arenaCenter.getY() - 1 + config.arenaLobbyHeight;
        buildLobbyDecor(lobbyY);
        Box searchBox = new Box(arenaCenter.getX() - 10, lobbyY - 1, arenaCenter.getZ() - 8,
            arenaCenter.getX() + 10, lobbyY + 7, arenaCenter.getZ() + 9);

        // Remove old carpet-button interaction entities and their floating labels.
        List<Entity> oldControls = arenaWorld.getEntitiesByClass(Entity.class, searchBox,
            entity -> entity.getCommandTags().stream().anyMatch(tag ->
                tag.startsWith("icerush_button_") || tag.startsWith("icerush_lobby_label_")));
        for (Entity old : oldControls) old.discard();

        String[] actions = {"join", "ready", "rules", "top", "stats", "lastwinner"};
        String[] labels = {"▶ VSTÚPIŤ", "✓ READY", "PRAVIDLÁ", "TOP 10", "ŠTATISTIKY", "POSLEDNÝ VÍŤAZ"};
        Formatting[] colors = {Formatting.AQUA, Formatting.GREEN, Formatting.YELLOW,
            Formatting.GOLD, Formatting.LIGHT_PURPLE, Formatting.WHITE};
        for (int i = 0; i < actions.length; i++) {
            String tag = "icerush_button_" + actions[i];
            final String actionTag = tag;
            List<ArmorStandEntity> existing = arenaWorld.getEntitiesByClass(ArmorStandEntity.class, searchBox,
                stand -> stand.getCommandTags().contains(actionTag));
            if (!existing.isEmpty()) continue;

            // Clickable character models are placed along the front edge of the lobby.
            double x = arenaCenter.getX() - 7.0 + i * 2.8;
            double z = arenaCenter.getZ() + 6.4;
            ArmorStandEntity npc = EntityType.ARMOR_STAND.create(arenaWorld);
            if (npc == null) continue;
            npc.refreshPositionAndAngles(x + 0.5, lobbyY + 1.0, z + 0.5, 180.0F, 0.0F);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setNoGravity(true);
            npc.setShowArms(true);
            npc.setHideBasePlate(true);
            ItemStack playerHead = new ItemStack(Items.PLAYER_HEAD);
            playerHead.getOrCreateNbt().putString("SkullOwner", "MHF_Steve");
            npc.equipStack(EquipmentSlot.HEAD, playerHead);
            ItemStack chest = new ItemStack(Items.LEATHER_CHESTPLATE);
            ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
            ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
            ((DyeableItem) Items.LEATHER_CHESTPLATE).setColor(chest, 0x168FD0);
            ((DyeableItem) Items.LEATHER_LEGGINGS).setColor(legs, 0x126A9E);
            ((DyeableItem) Items.LEATHER_BOOTS).setColor(boots, 0x168FD0);
            npc.equipStack(EquipmentSlot.CHEST, chest);
            npc.equipStack(EquipmentSlot.LEGS, legs);
            npc.equipStack(EquipmentSlot.FEET, boots);
            npc.setCustomName(Text.literal(labels[i]).formatted(colors[i], Formatting.BOLD));
            npc.setCustomNameVisible(true);
            npc.addCommandTag(tag);
            arenaWorld.spawnEntity(npc);
        }

        ensureLobbyText("icerush_lobby_title", arenaCenter.getX() + 0.5, lobbyY + 3.4, arenaCenter.getZ() - 5.5,
            Text.literal("TOSO  •  ICE RUSH").formatted(Formatting.AQUA, Formatting.BOLD));
        ensureLobbyText("icerush_lobby_subtitle", arenaCenter.getX() + 0.5, lobbyY + 2.8, arenaCenter.getZ() - 5.5,
            Text.literal("ĽADOVÁ ARÉNA  |  AŽ 16 HRÁČOV").formatted(Formatting.WHITE));
        ensureLobbyText("icerush_lobby_hint", arenaCenter.getX() + 0.5, lobbyY + 1.7, arenaCenter.getZ() + 7.0,
            Text.literal("KLIKNI NA POSTAVIČKU A VYBER SI AKCIU").formatted(Formatting.YELLOW, Formatting.BOLD));
    }

    private void buildLobbyDecor(int lobbyY) {
        int cx = arenaCenter.getX();
        int cz = arenaCenter.getZ();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                BlockState floor;
                if (Math.abs(x) <= 5 && Math.abs(z) <= 5) {
                    floor = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
                } else if (Math.abs(x) == 8 || Math.abs(z) == 8) {
                    floor = Blocks.BLUE_CONCRETE.getDefaultState();
                } else if (Math.abs(x) == 7 || Math.abs(z) == 7) {
                    floor = Blocks.CYAN_CONCRETE.getDefaultState();
                } else if ((Math.abs(x) + Math.abs(z)) % 4 == 0) {
                    floor = Blocks.LIGHT_BLUE_CONCRETE.getDefaultState();
                } else {
                    floor = Blocks.SMOOTH_QUARTZ.getDefaultState();
                }
                arenaWorld.setBlockState(new BlockPos(cx + x, lobbyY, cz + z), floor);
            }
        }
        for (int sx : new int[]{-7, 7}) {
            for (int sz : new int[]{-7, 7}) {
                for (int dy = 1; dy <= 3; dy++) {
                    arenaWorld.setBlockState(new BlockPos(cx + sx, lobbyY + dy, cz + sz),
                        (dy == 3 ? Blocks.CYAN_CONCRETE : Blocks.BLUE_CONCRETE).getDefaultState());
                }
                arenaWorld.setBlockState(new BlockPos(cx + sx, lobbyY + 4, cz + sz), Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // Lobby actions are represented by clickable NPCs instead of carpet pads.
    }

    private void ensureLobbyText(String tag, double x, double y, double z, Text text) {
        Box area = new Box(arenaCenter.getX() - 10, arenaCenter.getY() + config.arenaLobbyHeight - 2,
            arenaCenter.getZ() - 10, arenaCenter.getX() + 10, arenaCenter.getY() + config.arenaLobbyHeight + 9,
            arenaCenter.getZ() + 10);
        List<ArmorStandEntity> found = arenaWorld.getEntitiesByClass(ArmorStandEntity.class, area,
            stand -> stand.getCommandTags().contains(tag));
        ArmorStandEntity stand;
        if (found.isEmpty()) {
            stand = new ArmorStandEntity(EntityType.ARMOR_STAND, arenaWorld);
            stand.setPosition(x, y, z);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setCustomNameVisible(true);
            stand.addCommandTag(tag);
            arenaWorld.spawnEntity(stand);
        } else {
            stand = found.get(0);
        }
        stand.setCustomName(text);
    }

    public boolean isLobbyButton(Entity entity) {
        return entity.getCommandTags().stream().anyMatch(tag -> tag.startsWith("icerush_button_"));
    }

    public void handleLobbyButton(ServerPlayerEntity player, Entity entity) {
        String action = entity.getCommandTags().stream()
            .filter(tag -> tag.startsWith("icerush_button_"))
            .map(tag -> tag.substring("icerush_button_".length())).findFirst().orElse("");
        ServerCommandSource source = player.getCommandSource();
        try {
            switch (action) {
                case "join" -> join(source);
                case "ready" -> ready(source);
                case "rules" -> showRules(source);
                case "top" -> showTop(source);
                case "stats" -> showStats(source);
                case "lastwinner" -> showLastWinner(source);
                default -> player.sendMessage(Text.literal("Neznáme lobby tlačidlo."), false);
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            player.sendMessage(Text.literal("Tento úkon sa nepodarilo vykonať." ).formatted(Formatting.RED), false);
        }
    }

    private int clearSpots(ServerCommandSource source) {
        if (state != State.WAITING || arenaWorld == null) {
            source.sendError(Text.literal("Parkovacie miesta možno meniť iba v lobby."));
            return 0;
        }
        config.parkingSpotsCustomized = false;
        config.customParkingSpots.clear();
        config.save();
        initializeArenaPositions();
        source.sendFeedback(() -> Text.literal("Obnovené centrálne parkoviská: " + parkingSpots.size() + " miest. Počet sa bude vždy riadiť aktívnymi hráčmi mínus jedno."), false);
        return 1;
    }

    private int defaultSpots(ServerCommandSource source) {
        if (state != State.WAITING || arenaWorld == null) {
            source.sendError(Text.literal("Predvolené miesta možno obnoviť iba v lobby po vytvorení arény."));
            return 0;
        }
        config.parkingSpotsCustomized = false;
        config.customParkingSpots.clear();
        config.save();
        initializeArenaPositions();
        source.sendFeedback(() -> Text.literal("Obnovených je " + parkingSpots.size() + " predvolených parkovacích miest."), false);
        return 1;
    }

    private int showStats(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        IceRushStatsStore.PlayerStats stats = statsStore.get(player.getUuid());
        double winRate = stats.gamesPlayed == 0 ? 0.0 : stats.wins * 100.0 / stats.gamesPlayed;
        player.sendMessage(Text.literal("━━━━━━━━━━ TOSO • ICE RUSH ━━━━━━━━━━").formatted(Formatting.AQUA, Formatting.BOLD), false);
        player.sendMessage(Text.literal("          TVOJE ŠTATISTIKY").formatted(Formatting.WHITE, Formatting.BOLD), false);
        player.sendMessage(Text.literal("Výhry: ").formatted(Formatting.GOLD).append(Text.literal(String.valueOf(stats.wins)).formatted(Formatting.WHITE))
            .append(Text.literal("  │  Hry: ").formatted(Formatting.GRAY)).append(Text.literal(String.valueOf(stats.gamesPlayed)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Úspešnosť: ").formatted(Formatting.GREEN).append(Text.literal(String.format(java.util.Locale.ROOT, "%.1f%%", winRate)).formatted(Formatting.WHITE))
            .append(Text.literal("  │  Prehry: ").formatted(Formatting.GRAY)).append(Text.literal(String.valueOf(stats.losses)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Celkové body: ").formatted(Formatting.AQUA).append(Text.literal(String.valueOf(stats.points)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Turbo bonusy: ").formatted(Formatting.YELLOW).append(Text.literal(String.valueOf(stats.turboBonuses)).formatted(Formatting.WHITE))
            .append(Text.literal("  │  Prežité kolá: ").formatted(Formatting.GRAY)).append(Text.literal(String.valueOf(stats.roundsSurvived)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Rekord rýchlosti: ").formatted(Formatting.LIGHT_PURPLE).append(Text.literal(String.format(java.util.Locale.ROOT, "%.3f", stats.speedRecord)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Najlepšie parkovanie: ").formatted(Formatting.BLUE).append(Text.literal(stats.bestParkingTicks == 0 ? "Zatiaľ bez záznamu" : String.format(java.util.Locale.ROOT, "%.2f s", stats.bestParkingTicks / 20.0)).formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA), false);
        player.sendMessage(Text.literal("Ďalšie: /icerush top  •  /icerush achievements").formatted(Formatting.GRAY), false);
        return 1;
    }

    private int showTop(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("━━━━━━━━━━ TOSO • ICE RUSH ━━━━━━━━━━").formatted(Formatting.AQUA, Formatting.BOLD), false);
        source.sendFeedback(() -> Text.literal("                 TOP 10 HRÁČOV").formatted(Formatting.GOLD, Formatting.BOLD), false);
        List<Map.Entry<UUID, IceRushStatsStore.PlayerStats>> ranking = statsStore.top(10);
        if (ranking.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Rebríček je zatiaľ prázdny. Odohraj prvú hru!").formatted(Formatting.GRAY), false);
        }
        int rank = 1;
        for (Map.Entry<UUID, IceRushStatsStore.PlayerStats> entry : ranking) {
            ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(entry.getKey());
            String name = online == null ? (entry.getValue().playerName.isBlank() ? entry.getKey().toString().substring(0, 8) : entry.getValue().playerName) : online.getName().getString();
            int place = rank++;
            Formatting color = place == 1 ? Formatting.GOLD : place == 2 ? Formatting.GRAY : place == 3 ? Formatting.YELLOW : Formatting.WHITE;
            source.sendFeedback(() -> Text.literal(String.format(java.util.Locale.ROOT, "%02d", place) + "  " + name)
                .formatted(color, Formatting.BOLD).append(Text.literal("  •  " + entry.getValue().points + " bodov")
                .formatted(Formatting.AQUA)).append(Text.literal("  •  " + entry.getValue().wins + " výhier").formatted(Formatting.GRAY)), false);
        }
        source.sendFeedback(() -> Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA), false);
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
        player.sendMessage(Text.literal("━━━━━━━━━━ TOSO • ICE RUSH ━━━━━━━━━━").formatted(Formatting.AQUA, Formatting.BOLD), false);
        player.sendMessage(Text.literal("             ÚSPECHY A ACHIEVEMENTY").formatted(Formatting.GOLD, Formatting.BOLD), false);
        String[][] goals = {
            {"Prvá výhra", "Získaj svoju prvú výhru", stats.wins >= 1 ? "1/1" : "0/1"},
            {"Ice King", "Vyhraj 10 hier", stats.wins + "/10 výhier"},
            {"Turbo Master", "Získaj 25 turbo bonusov", stats.turboBonuses + "/25 bonusov"},
            {"Speed Demon", "Dosiahni referenčnú rýchlosť " + String.format(java.util.Locale.ROOT, "%.2f", config.speedReference), String.format(java.util.Locale.ROOT, "%.3f", stats.speedRecord)},
            {"Survivor", "Preži 10 kôl", stats.roundsSurvived + "/10 kôl"},
            {"Perfect Parking", "Zaparkuj za najviac 5 sekúnd", stats.bestParkingTicks == 0 ? "Bez záznamu" : String.format(java.util.Locale.ROOT, "%.2f s", stats.bestParkingTicks / 20.0)}
        };
        for (String[] goal : goals) {
            boolean unlocked = stats.achievements.contains(goal[0]);
            Text line = Text.literal(unlocked ? "✓ " : "○ ").formatted(unlocked ? Formatting.GREEN : Formatting.DARK_GRAY)
                .append(Text.literal(goal[0]).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" — " + goal[1] + " [" + goal[2] + "]")
                    .formatted(unlocked ? Formatting.GREEN : Formatting.GRAY));
            player.sendMessage(line, false);
        }
        player.sendMessage(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA), false);
        return 1;
    }

    private int countVisibleParkingBlocks() {
        if (arenaWorld == null || state != State.PARKING) return 0;
        int count = Math.min(Math.max(0, activeCount() - 1), parkingSpots.size());
        int blocks = 0;
        for (int i = 0; i < count; i++) {
            Vec3d spot = parkingSpots.get(i);
            BlockPos center = new BlockPos((int) Math.floor(spot.x), (int) Math.floor(spot.y) - 1,
                (int) Math.floor(spot.z));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (arenaWorld.getBlockState(center.add(dx, 0, dz)).isOf(Blocks.LIGHT_BLUE_CONCRETE)) blocks++;
                }
            }
        }
        return blocks;
    }

    private int status(ServerCommandSource source) {
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        int activeParkingSpots = state == State.PARKING ? Math.max(0, activeCount() - 1) : 0;
        int visibleParkingBlocks = countVisibleParkingBlocks();
        source.sendFeedback(() -> Text.literal("ICE RUSH | Stav: " + state + " | Kolo: " + round
            + " | Hráči: " + activeCount() + "/" + participants.size()
            + " | Ready: " + readyCount + " | Dostupné miesta: " + parkingSpots.size()
            + " | Aktívne parkoviská: " + activeParkingSpots
            + " | Bloky: " + visibleParkingBlocks + "/" + (activeParkingSpots * 9)), false);
        return 1;
    }

    private int reset(ServerCommandSource source) {
        resetGame(source.getServer(), true);
        source.sendFeedback(() -> Text.literal("ICE RUSH bolo resetované administrátorom."), true);
        return 1;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        if (state == State.WAITING) {
            if (arenaWorld != null && lobbyPosition != null) {
                player.interactionManager.changeGameMode(GameMode.ADVENTURE);
                teleportToLobby(player);
            }
            sendLobbyMenu(player);
            return;
        }
        Participant existing = participants.get(player.getUuid());
        // Every connection made after the match has started is a spectator,
        // including a reconnect by an already eliminated competitor.
        lateSpectators.add(player.getUuid());
        phaseBar.addPlayer(player);
        player.interactionManager.changeGameMode(GameMode.SPECTATOR);
        teleportToLobby(player);
        player.sendMessage(Text.literal(existing != null && existing.eliminated
            ? "Už si bol vyradený. Do ďalšej hry zostávaš divákom."
            : "ICE RUSH už prebieha. Sleduješ zápas ako divák až do jeho skončenia.").formatted(Formatting.YELLOW), false);
        player.sendMessage(Text.literal("Tvoj režim: " + (player.isSpectator() ? "DIVÁK" : "HRÁČ")), false);
    }

    public void onPlayerDisconnect(UUID playerId, MinecraftServer server) {
        BoatEntity disconnectedBoat = playerBoats.remove(playerId);
        if (disconnectedBoat != null && !disconnectedBoat.isRemoved()) disconnectedBoat.discard();
        ServerBossBar disconnectedBar = speedBars.remove(playerId);
        if (disconnectedBar != null) disconnectedBar.clearPlayers();
        if (state == State.WAITING || (state == State.COUNTDOWN && lobbyCountdown)) {
            participants.remove(playerId);
            if (state == State.COUNTDOWN) countdownNeedsValidation = true;
        } else {
            Participant participant = participants.get(playerId);
            if (participant != null && !participant.eliminated) {
                if (participant.parked && !participant.parkingRewarded) {
                    participant.points += 10;
                    participant.parkingRewarded = true;
                    occupiedSpots.remove(participant.parkingSpot);
                    participant.parked = false;
                    participant.parkingSpot = -1;
                }
                int placeBeforeDisconnect = activeCount();
                if (placeBeforeDisconnect == 2) participant.points += 60;
                else if (placeBeforeDisconnect == 3) participant.points += 40;
                participant.eliminated = true;
                String name = participant.playerName.isBlank() ? playerId.toString().substring(0, 8) : participant.playerName;
                broadcast(server, Text.literal(name + " opustil zápas a vypadáva.").formatted(Formatting.RED));
            }
            lateSpectators.remove(playerId);
        }

        // Reconcile the parking phase immediately after a competitor leaves.
        // If the remaining occupied pads already satisfy the reduced player
        // count, resolve the phase now instead of hiding a pad under a parked
        // player on the next tick.
        if (state == State.PARKING) {
            int requiredPads = Math.max(0, activeCount() - 1);
            if (occupiedSpots.size() >= requiredPads) {
                finishParking(server);
            } else {
                visibleParkingPadCount = -1;
            }
        }

        // A disconnect during a live round must resolve the match immediately
        // when one or fewer active competitors remain.
        if (state != State.WAITING && state != State.FINISHED
            && !(state == State.COUNTDOWN && lobbyCountdown) && activeCount() <= 1) {
            declareWinner(server);
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
        broadcastTitle(server, String.valueOf(config.countdownSeconds), "PRIPRAV SA!", 20);
    }

    public void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) updateSidebar(server);
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
                    broadcastTitle(server, String.valueOf(secondsLeft), "PRIPRAV SA!", 20);
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
            tickRaceMusic(server);
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
            // Keep the parking duration hidden; no shrinking bar reveals the deadline.
            phaseBar.setPercent(1.0F);
            if (server.getTicks() % 20 == 0) updateHud(server);
            enforceBoatSeats(server);
            int requiredPadsNow = Math.max(0, activeCount() - 1);
            if (visibleParkingPadCount != requiredPadsNow) showParkingPads();
            tickCracks(server);
            tickParkingVisuals(server);
            updateParking(server);
            if (activeCount() <= 1) {
                awardSurvivingParkingRewards(server);
                declareWinner(server);
                return;
            }
            timer--;
            int requiredSpots = Math.max(0, activeCount() - 1);
            if (occupiedSpots.size() >= requiredSpots || timer <= 0) finishParking(server);
            return;
        }
        if (state == State.FINISHED) {
            if (celebrationTicks > 0 && server.getTicks() % 5 == 0) {
                celebrationTicks--;
                ServerPlayerEntity winnerPlayer = winner == null ? null : server.getPlayerManager().getPlayer(winner);
                if (winnerPlayer != null && arenaWorld != null) {
                    double x = winnerPlayer.getX();
                    double y = winnerPlayer.getY() + 1.0;
                    double z = winnerPlayer.getZ();
                    arenaWorld.spawnParticles(ParticleTypes.FIREWORK, x, y, z, 10, 0.8, 0.7, 0.8, 0.08);
                    arenaWorld.spawnParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 8, 0.7, 0.7, 0.7, 0.04);
                    arenaWorld.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 5, 0.5, 0.8, 0.5, 0.03);
                }
            }
            timer--;
            if (timer <= 0) resetGame(server, false);
        }
    }

    private void startRound(MinecraftServer server) {
        if (round == 0) resultsCommitted = false;
        boatsLocked = false;
        lobbyCountdown = false;
        round++;
        for (Participant p : participants.values()) {
            p.parked = false;
            p.parkingSpot = -1;
            p.ready = false;
            p.parkingRewarded = false;
            p.survivalRewarded = false;
            p.appliedSpeedScale = 1.0;
        }
        occupiedSpots.clear();
        state = State.RACING;
        timer = ThreadLocalRandom.current().nextInt(config.minRideSeconds * 20, config.maxRideSeconds * 20 + 1);
        spawnMissingRoundBoats(server);
        setupSpeedBars(server);
        phaseBar.setName(Text.literal("JAZDA"));
        phaseBar.setColor(BossBar.Color.BLUE);
        phaseBar.setPercent(1.0F);
        addPhaseBarPlayers(server);
        roundTicks = 0;
        prepareRoundModifiers(server);
        broadcast(server, Text.literal("ŠTART!").formatted(Formatting.GREEN, Formatting.BOLD));
        broadcastTitle(server, "ŠTART!", "ICE RUSH", 30);
        playSoundToActivePlayers(server, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.55F, 1.35F);
    }

    private void beginParking(MinecraftServer server) {
        clearSpeedBars();
        phaseBar.setName(Text.literal("PARKOVANIE"));
        phaseBar.setColor(BossBar.Color.YELLOW);
        phaseBar.setPercent(1.0F);
        Collections.shuffle(parkingSpots);
        showParkingPads();
        state = State.PARKING;
        timer = config.parkingSeconds * 20;
        occupiedSpots.clear();
        for (Participant p : participants.values()) { p.parked = false; p.parkingRewarded = false; p.parkingSpot = -1; }
        broadcast(server, Text.literal("PARKOVANIE! Nájdeš si voľné miesto skôr než ostatní?").formatted(Formatting.GOLD, Formatting.BOLD));
        playSoundToActivePlayers(server, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.65F, 0.82F);
    }

    private void showParkingPads() {
        hideParkingPads();
        if (arenaWorld == null || arenaCenter == null) return;
        int count = Math.max(0, activeCount() - 1);
        if (parkingSpots.size() < count) {
            IceRushMod.LOGGER.error("ICE RUSH has {} parking positions but {} are required for {} active players",
                parkingSpots.size(), count, activeCount());
            count = parkingSpots.size();
        }
        visibleParkingPadCount = count;
        // Reveal the center only while PARKING is active.
        int cx = arenaCenter.getX();
        int cy = arenaCenter.getY() - 1;
        int cz = arenaCenter.getZ();
        int inner = (int) Math.ceil(arenaInnerRadius());
        for (int dx = -inner; dx <= inner; dx++) {
            for (int dz = -inner; dz <= inner; dz++) {
                if ((double) dx * dx + (double) dz * dz >= arenaInnerRadius() * arenaInnerRadius()) continue;
                BlockPos pos = new BlockPos(cx + dx, cy, cz + dz);
                parkingOriginalStates.putIfAbsent(pos, arenaWorld.getBlockState(pos));
                arenaWorld.setBlockState(pos, Blocks.PACKED_ICE.getDefaultState());
                // Center ice stays visible during parking; only parking pads animate.
            }
        }
        for (int i = 0; i < count; i++) {
            Vec3d spot = parkingSpots.get(i);
            BlockPos center = new BlockPos((int) Math.floor(spot.x), (int) Math.floor(spot.y) - 1, (int) Math.floor(spot.z));
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
        parkingAnimationBlocks.clear();
        parkingAnimationIndex = 0;
        visibleParkingPadCount = -1;
    }

    private void tickParkingVisuals(MinecraftServer server) {
        // The 3x3 parking pads are solid blocks; no particle cloud obscures them.
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
            BoatEntity assignedBoat = playerBoats.get(participant.id);
            Entity vehicle = player.getVehicle();
            if (assignedBoat == null || vehicle != assignedBoat || !assignedBoat.hasPassenger(player)) continue;
            Vec3d boatPosition = assignedBoat.getPos();
            for (int i = 0; i < Math.min(parkingSpots.size(), Math.max(0, activeCount() - 1)); i++) {
                if (occupiedSpots.contains(i)) continue;
                if (boatPosition.squaredDistanceTo(parkingSpots.get(i)) <= config.parkingRadius * config.parkingRadius) {
                    participant.parked = true;
                    participant.parkingSpot = i;
                    participant.parkingTimeTicks = Math.max(1, config.parkingSeconds * 20 - timer);
                    occupiedSpots.add(i);
                    player.sendMessage(Text.literal("Parkovacie miesto obsadené!").formatted(Formatting.GREEN), true);
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 0.8F, 1.65F);
                    break;
                }
            }
        }
    }

    private void awardSurvivingParkingRewards(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated || !participant.parked || participant.parkingRewarded) continue;
            if (!participant.parkingRewarded) {
                participant.points += 10;
                participant.parkingRewarded = true;
            }
            if (!participant.survivalRewarded) {
                participant.points += 5;
                participant.roundsSurvived++;
                participant.survivalRewarded = true;
            }
            if (participant.parkingTimeTicks > 0 &&
                (participant.bestParkingTicks == 0 || participant.parkingTimeTicks < participant.bestParkingTicks)) {
                participant.bestParkingTicks = participant.parkingTimeTicks;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) player.sendMessage(Text.literal("+10 parkovanie +5 prežitie kola. Spolu: " + participant.points).formatted(Formatting.GREEN), false);
        }
    }

    private void finishParking(MinecraftServer server) {
        hideParkingPads();
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (participant.parked) {
                if (!participant.parkingRewarded) {
                    participant.points += 10;
                    participant.parkingRewarded = true;
                }
                if (!participant.survivalRewarded) {
                    participant.points += 5;
                    participant.roundsSurvived++;
                    participant.survivalRewarded = true;
                }
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
                String eliminatedName = player == null ? participant.playerName : player.getName().getString();
                broadcast(server, Text.literal(eliminatedName + " vypadáva z kola!").formatted(Formatting.RED));
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
                    player.playSound(SoundEvents.ENTITY_IRON_GOLEM_HURT, SoundCategory.PLAYERS, 0.65F, 0.75F);
                    sendTitle(player, "VYPADÁVAŠ!", "Nenašiel si parkovacie miesto.");
                    player.interactionManager.changeGameMode(GameMode.SPECTATOR);
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

    private void updateSidebar(MinecraftServer server) {
        updateLobbyLeaderboard(server);

        // Use the scoreboard API instead of building commands from display text.
        // In Minecraft 1.20.1, quoted fake-player names containing spaces are not
        // parsed as scoreboard holders, which caused repeated command syntax errors.
        var scoreboard = server.getScoreboard();
        var objective = scoreboard.getNullableObjective("icerush");
        if (objective == null) {
            objective = scoreboard.addObjective(
                "icerush",
                net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                Text.literal("ICE RUSH").formatted(Formatting.AQUA, Formatting.BOLD),
                net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER
            );
        }
        scoreboard.setObjectiveSlot(net.minecraft.scoreboard.Scoreboard.getDisplaySlotId("sidebar"), objective);
        // Clear the legacy combined round/timer entry before rebuilding the timer-free sidebar.
        String legacyTimerHolder = String.valueOf((char) 0x2062);
        scoreboard.resetPlayerScore(legacyTimerHolder, objective);
        if (scoreboard.getPlayerTeam(legacyTimerHolder) != null) {
            scoreboard.clearPlayerTeam(legacyTimerHolder);
        }

        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        String phase = switch (state) {
            case WAITING -> "ČAKÁREŇ";
            case COUNTDOWN -> "ŠTART";
            case RACING -> "JAZDA";
            case PARKING -> "PARKOVANIE";
            case FINISHED -> "VÝSLEDOK";
        };
        String playerLabel = state == State.WAITING ? participants.size() + "/" + config.maxPlayers : activeCount() + " aktívnych";
        String readyLabel = state == State.WAITING ? readyCount + "/" + participants.size() : "—";
        String[] lines = {
            "TOSO • ICE RUSH",
            "Fáza: " + phase,
            "Kolo: " + (state == State.WAITING ? "—" : round),
            "Hráči: " + playerLabel,
            "READY: " + readyLabel,
            "Parkoviská: " + (state == State.PARKING ? Math.max(0, activeCount() - 1) : "—")
        };

        // Invisible unique holders keep the sidebar safe for accents, spaces and formatting.
        String[] holders = {String.valueOf((char) 0x200B), String.valueOf((char) 0x200C),
            String.valueOf((char) 0x200D), String.valueOf((char) 0x2060),
            String.valueOf((char) 0x2061), String.valueOf((char) 0x2062)};
        for (int i = 0; i < lines.length; i++) {
            String teamName = "icerush_line_" + (i + 1);
            var team = scoreboard.getTeam(teamName);
            if (team == null) team = scoreboard.addTeam(teamName);

            String holder = holders[i];
            var previousTeam = scoreboard.getPlayerTeam(holder);
            if (previousTeam != team) {
                if (previousTeam != null) scoreboard.clearPlayerTeam(holder);
                scoreboard.addPlayerToTeam(holder, team);
            }

            Formatting lineColor = switch (i) {
                case 0 -> Formatting.AQUA;
                case 1 -> Formatting.YELLOW;
                case 2 -> Formatting.GOLD;
                case 3 -> Formatting.WHITE;
                case 4 -> Formatting.GREEN;
                default -> Formatting.BLUE;
            };
            var prefix = Text.literal(lines[i]).formatted(lineColor);
            if (i == 0) prefix.formatted(Formatting.BOLD);
            team.setColor(lineColor);
            team.setPrefix(prefix);
            team.setSuffix(Text.empty());
            scoreboard.getPlayerScore(holder, objective).setScore(lines.length - i);
        }
        sidebarInitialized = true;
    }

    private void updateLobbyLeaderboard(MinecraftServer server) {
        if (arenaWorld == null || arenaCenter == null) return;
        int lobbyY = arenaCenter.getY() - 1 + config.arenaLobbyHeight;
        Vec3d boardCenter = new Vec3d(arenaCenter.getX() + 7.0, lobbyY + 3.0, arenaCenter.getZ());
        boolean lobbyAreaLoaded = server.getPlayerManager().getPlayerList().stream()
            .anyMatch(player -> player.getServerWorld() == arenaWorld
                && player.getPos().squaredDistanceTo(boardCenter) < 128.0 * 128.0);
        if (!lobbyAreaLoaded) return;
        if (!lobbyBoardBuilt) {
            for (int dy = 1; dy <= 7; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockState boardBlock = Math.abs(dz) == 5 || dy == 7
                        ? Blocks.CYAN_CONCRETE.getDefaultState() : Blocks.BLUE_CONCRETE.getDefaultState();
                    arenaWorld.setBlockState(new BlockPos(arenaCenter.getX() + 7, lobbyY + dy, arenaCenter.getZ() + dz), boardBlock);
                }
            }
            for (int dz : new int[]{-5, 5}) {
                arenaWorld.setBlockState(new BlockPos(arenaCenter.getX() + 7, lobbyY + 7, arenaCenter.getZ() + dz),
                    Blocks.SEA_LANTERN.getDefaultState());
            }
            lobbyBoardBuilt = true;
        }

        List<Map.Entry<UUID, IceRushStatsStore.PlayerStats>> entries = statsStore.top(10);
        Box boardBox = new Box(arenaCenter.getX() + 5, lobbyY - 1, arenaCenter.getZ() - 7,
            arenaCenter.getX() + 9, lobbyY + 10, arenaCenter.getZ() + 8);
        for (int row = 0; row <= 10; row++) {
            String tag = "icerush_top_row_" + row;
            final int rowIndex = row;
            List<ArmorStandEntity> found = arenaWorld.getEntitiesByClass(ArmorStandEntity.class, boardBox,
                stand -> stand.getCommandTags().contains(tag));
            ArmorStandEntity label;
            if (found.isEmpty()) {
                label = new ArmorStandEntity(EntityType.ARMOR_STAND, arenaWorld);
                label.setPosition(arenaCenter.getX() + 6.35, lobbyY + 5.5 - row * 0.45, arenaCenter.getZ() - 4.5);
                label.setInvisible(true);
                label.setNoGravity(true);
                label.setInvulnerable(true);
                label.setCustomNameVisible(true);
                label.addCommandTag(tag);
                arenaWorld.spawnEntity(label);
            } else {
                label = found.get(0);
            }

            Text line;
            if (rowIndex == 0) {
                line = Text.literal("TOP 10 — ICE RUSH").formatted(Formatting.GOLD, Formatting.BOLD);
            } else if (rowIndex - 1 < entries.size()) {
                Map.Entry<UUID, IceRushStatsStore.PlayerStats> entry = entries.get(rowIndex - 1);
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(entry.getKey());
                String name = online == null
                    ? (entry.getValue().playerName.isBlank() ? entry.getKey().toString().substring(0, 8) : entry.getValue().playerName)
                    : online.getName().getString();
                if (name.length() > 12) name = name.substring(0, 11) + "…";
                Formatting rankColor = rowIndex == 1 ? Formatting.GOLD
                    : rowIndex == 2 ? Formatting.GRAY : rowIndex == 3 ? Formatting.YELLOW : Formatting.WHITE;
                line = Text.literal(rowIndex + ". " + name + " — " + entry.getValue().points + " b")
                    .formatted(rankColor, Formatting.BOLD);
            } else {
                line = Text.literal(rowIndex + ". —").formatted(Formatting.DARK_GRAY);
            }
            label.setCustomName(line);
        }

        String winnerTag = "icerush_last_winner_display";
        List<ArmorStandEntity> winnerLabels = arenaWorld.getEntitiesByClass(ArmorStandEntity.class, boardBox,
            stand -> stand.getCommandTags().contains(winnerTag));
        ArmorStandEntity winnerLabel;
        if (winnerLabels.isEmpty()) {
            winnerLabel = new ArmorStandEntity(EntityType.ARMOR_STAND, arenaWorld);
            winnerLabel.setPosition(arenaCenter.getX() + 6.35, lobbyY + 0.8, arenaCenter.getZ() + 6.5);
            winnerLabel.setInvisible(true);
            winnerLabel.setNoGravity(true);
            winnerLabel.setInvulnerable(true);
            winnerLabel.setCustomNameVisible(true);
            winnerLabel.addCommandTag(winnerTag);
            arenaWorld.spawnEntity(winnerLabel);
        } else {
            winnerLabel = winnerLabels.get(0);
        }
        winnerLabel.setCustomName(Text.literal("POSLEDNÝ VÍŤAZ: " + statsStore.lastWinner()).formatted(Formatting.AQUA, Formatting.BOLD));
    }

    private void updateHud(MinecraftServer server) {
        String phase = state == State.PARKING ? "PARKOVANIE" : "JAZDA";
        Text hud = Text.literal("ICE RUSH  |  Hráči: " + activeCount() + "  |  " + phase);
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null && !participant.eliminated) player.sendMessage(hud, true);
        }
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) player.sendMessage(hud, true);
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

    /**
     * Quiet, original 16-note ambient motif built from vanilla chime sounds.
     * It is sent directly to each active competitor; no external resource pack
     * or client-side mod is required.
     */
    private void tickRaceMusic(MinecraftServer server) {
        if (roundTicks % 12 != 0) return;
        float[] notes = {0.75F, 0.89F, 1.12F, 0.89F, 1.0F, 1.33F, 1.12F, 0.89F,
            0.67F, 0.89F, 1.0F, 1.19F, 1.0F, 0.89F, 0.75F, 0.67F};
        float pitch = notes[(roundTicks / 12) % notes.length];
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null && player.getVehicle() == playerBoats.get(participant.id)) {
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.MUSIC, 0.10F, pitch);
            }
        }
    }

    private void playSoundToActivePlayers(MinecraftServer server, net.minecraft.sound.SoundEvent sound,
                                          SoundCategory category, float volume, float pitch) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) player.playSound(sound, category, volume, pitch);
        }
    }

    private void updateSpeedBars(MinecraftServer server) {
        if (roundTicks < config.speedGraceSeconds * 20 || server.getTicks() % 5 != 0) return; // prvých 5 sekúnd sa rýchlosť nevyhodnocuje
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)
                || boat != playerBoats.get(participant.id) || !boat.hasPassenger(player)) continue;
            // Meranie rýchlosti nesmie prepisovať fyziku vanilla lode.
            // Opakované setVelocity() z HUD logiky môže prepisovať pohyb, ktorý
            // práve vypočítal BoatEntity na základe vstupov hráča.
            double speed = boat.getVelocity().horizontalLength();
            participant.speedRecord = Math.max(participant.speedRecord, speed);
            double normalizedSpeed = Math.max(0.0, Math.min(1.0, speed / config.speedReference));
            int availableSteps = (100 - config.minimumSpeedPercent) / 5;
            int speedTier = (int) Math.floor(normalizedSpeed * availableSteps);
            participant.speedPercent = Math.max(config.minimumSpeedPercent,
                Math.min(100, config.minimumSpeedPercent + speedTier * 5));
            ServerBossBar bar = speedBars.get(participant.id);
            // A participant can briefly outlive its HUD bar during reconnect/cleanup.
            // Never let a missing presentation object interrupt the authoritative game tick.
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
            participant.appliedSpeedScale = 1.0;
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
            BoatEntity assignedBoat = playerBoats.get(participant.id);
            if (player != null && assignedBoat != null && player.getVehicle() == assignedBoat
                && assignedBoat.hasPassenger(player) && assignedBoat.getY() < arenaCenter.getY() - 2) {
                int placeBeforeElimination = activeCount();
                if (placeBeforeElimination == 2) participant.points += 60;
                else if (placeBeforeElimination == 3) participant.points += 40;
                participant.eliminated = true;
                broadcast(server, Text.literal(player.getName().getString() + " vypadáva cez prasknutý ľad!").formatted(Formatting.RED));
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
                sendTitle(player, "VYPADÁVAŠ!", "Prepadol si sa cez prasknutý ľad.");
                player.interactionManager.changeGameMode(GameMode.SPECTATOR);
                player.sendMessage(Text.literal("VYPADÁVAŠ! Prepadol si sa cez prasknutý ľad. Body: " + participant.points).formatted(Formatting.RED, Formatting.BOLD), false);
            }
        }
    }

    private void tickTurboExpiry(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated || participant.turboTicks <= 0) continue;
            BoatEntity boat = playerBoats.get(participant.id);
            participant.turboTicks--;
            if (participant.turboTicks == 0 && participant.turboActive) {
                if (boat != null && !boat.isRemoved()) boat.setVelocity(boat.getVelocity().multiply(1.0 / 1.05));
                participant.turboActive = false;
            }
        }
    }

    private void tickTurbo(MinecraftServer server) {
        tickTurboExpiry(server);
        if (roundTicks < config.speedGraceSeconds * 20 || server.getTicks() % 5 != 0) return;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)
                || boat != playerBoats.get(participant.id) || !boat.hasPassenger(player)) continue;
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
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.8F, 1.8F);
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
            if (player == null || !(player.getVehicle() instanceof BoatEntity boat)
                || boat != playerBoats.get(participant.id) || !boat.hasPassenger(player)) continue;
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

    /**
     * Resolve and load the exact block column below a boat start. Restore the
     * intended blue-ice surface and clear the boat/player collision space.
     */
    private Vec3d prepareBoatStartPosition(Vec3d requested) {
        BlockPos floor = BlockPos.ofFloored(requested.x, requested.y - 0.01, requested.z);
        arenaWorld.getChunk(floor);
        arenaWorld.setBlockState(floor, Blocks.BLUE_ICE.getDefaultState());
        arenaWorld.setBlockState(floor.up(), Blocks.AIR.getDefaultState());
        arenaWorld.setBlockState(floor.up(2), Blocks.AIR.getDefaultState());
        return new Vec3d(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
    }

    /**
     * A single server-authoritative path for creating, moving and mounting
     * round boats. The player is never left teleported above an unmounted boat.
     */
    private BoatEntity placeAndMountRoundBoat(ServerPlayerEntity player, Participant participant,
                                               Vec3d requested, BoatEntity boat) {
        Vec3d pos = prepareBoatStartPosition(requested);

        if (player.hasVehicle()) player.stopRiding();
        if (boat != null && !boat.isRemoved()) boat.removeAllPassengers();

        if (boat == null || boat.isRemoved()) {
            boat = new BoatEntity(EntityType.BOAT, arenaWorld);
            boat.refreshPositionAndAngles(pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
            boat.setVelocity(Vec3d.ZERO);
            if (!arenaWorld.spawnEntity(boat)) {
                IceRushMod.LOGGER.error("ICE RUSH could not spawn boat for {} at {}",
                    player.getGameProfile().getName(), pos);
                player.teleport(arenaWorld, pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
                player.setVelocity(Vec3d.ZERO);
                return null;
            }
        } else {
            boat.refreshPositionAndAngles(pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
            boat.setVelocity(Vec3d.ZERO);
        }

        playerBoats.put(participant.id, boat);
        participant.appliedSpeedScale = 1.0;
        participant.turboTicks = 0;
        participant.turboActive = false;
        player.setVelocity(Vec3d.ZERO);

        boolean mounted = player.startRiding(boat, true);
        if (!mounted || player.getVehicle() != boat || !boat.hasPassenger(player)) {
            // Safe fallback: the player stands on the repaired ice, never in mid-air.
            player.teleport(arenaWorld, pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
            player.setVelocity(Vec3d.ZERO);
            mounted = player.startRiding(boat, true);
        }
        if (!mounted || player.getVehicle() != boat || !boat.hasPassenger(player)) {
            player.teleport(arenaWorld, pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
            player.setVelocity(Vec3d.ZERO);
            IceRushMod.LOGGER.error("ICE RUSH could not mount {} on boat {} at safe start {}",
                player.getGameProfile().getName(), boat.getUuid(), pos);
        }
        return boat;
    }

    private void spawnRoundBoats(MinecraftServer server) {
        if (arenaWorld == null || startPositions.isEmpty()) return;
        List<Vec3d> freeStarts = new ArrayList<>(startPositions);
        Collections.shuffle(freeStarts);
        int index = 0;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            if (index >= freeStarts.size()) {
                IceRushMod.LOGGER.error("Not enough ICE RUSH start positions for active players");
                break;
            }
            Vec3d pos = freeStarts.get(index++);
            placeAndMountRoundBoat(player, participant, pos, playerBoats.get(participant.id));
        }
    }

    private void spawnMissingRoundBoats(MinecraftServer server) {
        if (arenaWorld == null || startPositions.isEmpty()) return;
        // Every round starts on the driving ring, never in the previous parking spot.
        List<Vec3d> freeStarts = new ArrayList<>(startPositions);
        Collections.shuffle(freeStarts);
        int index = 0;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            if (index >= freeStarts.size()) {
                IceRushMod.LOGGER.error("Not enough ICE RUSH start positions for active players");
                break;
            }
            Vec3d pos = freeStarts.get(index++);
            placeAndMountRoundBoat(player, participant, pos, playerBoats.get(participant.id));
        }
    }

    private void enforceBoatSeats(MinecraftServer server) {
        if (arenaWorld == null || startPositions.isEmpty()) return;
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            BoatEntity boat = playerBoats.get(participant.id);
            boolean correctlySeated = boat != null && !boat.isRemoved()
                && player.getVehicle() == boat && boat.hasPassenger(player);
            if (correctlySeated) continue;

            // Never respawn a missing boat at the player's possibly-falling coordinates.
            Vec3d safePosition = startPositions.get(0);
            double nearestDistance = Double.MAX_VALUE;
            for (Vec3d candidate : startPositions) {
                double dx = candidate.x - player.getX();
                double dz = candidate.z - player.getZ();
                double distance = dx * dx + dz * dz;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    safePosition = candidate;
                }
            }
            placeAndMountRoundBoat(player, participant, safePosition, boat);
        }
    }

    /** Keep active competitors seated: vanilla dismounts boats when the rider sneaks. */
    public boolean shouldPreventDismount(ServerPlayerEntity player) {
        if (state != State.COUNTDOWN && state != State.RACING && state != State.PARKING) return false;
        Participant participant = participants.get(player.getUuid());
        if (participant == null || participant.eliminated) return false;
        BoatEntity assignedBoat = playerBoats.get(participant.id);
        return assignedBoat != null && !assignedBoat.isRemoved()
            && player.getVehicle() == assignedBoat && assignedBoat.hasPassenger(player);
    }

    private void declareWinner(MinecraftServer server) {
        hideParkingPads();
        resetCracks();
        clearSpeedBars();
        phaseBar.clearPlayers();
        winner = participants.values().stream().filter(p -> !p.eliminated).map(p -> p.id).findFirst().orElse(null);
        state = State.FINISHED;
        timer = config.finishHoldSeconds * 20;

        // The race is over: release every competitor immediately. Do not keep
        // enforcing seats during the celebration, and do not leave eliminated
        // players trapped in spectator mode after the result is declared.
        for (UUID id : participants.keySet()) {
            ServerPlayerEntity competitor = server.getPlayerManager().getPlayer(id);
            if (competitor != null) {
                // Use the same ordered lobby-return path as /icerush leave:
                // dismount, teleport, switch to Adventure, then show the menu.
                // Changing mode after teleport also avoids the final position
                // transition racing the game-mode update on tracking clients.
                returnToLobby(competitor);
            }
        }
        for (UUID id : lateSpectators) {
            ServerPlayerEntity spectator = server.getPlayerManager().getPlayer(id);
            if (spectator != null) {
                returnToLobby(spectator);
            }
        }
        for (BoatEntity boat : playerBoats.values()) {
            if (!boat.isRemoved()) {
                boat.removeAllPassengers();
                boat.discard();
            }
        }
        playerBoats.clear();

        if (winner != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(winner);
            String name = player == null ? "Víťaz" : player.getName().getString();
            broadcast(server, Text.literal("🏆 " + name + " VYHRÁVA ICE RUSH!").formatted(Formatting.GOLD, Formatting.BOLD));
            Participant winnerParticipant = participants.get(winner);
            if (winnerParticipant != null) {
                if (round > 0 && !winnerParticipant.survivalRewarded) {
                    winnerParticipant.points += 5;
                    winnerParticipant.roundsSurvived++;
                    winnerParticipant.survivalRewarded = true;
                }
                winnerParticipant.points += 100;
            }
            celebrationTicks = 100;
            if (player != null) {
                sendTitle(player, "VÍŤAZ!", "Vyhrávaš ICE RUSH!");
                if (arenaWorld != null) {
                    arenaWorld.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 1.0, player.getZ(), 100, 1.2, 1.0, 1.2, 0.12);
                    arenaWorld.spawnParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.0, player.getZ(), 60, 1.0, 1.0, 1.0, 0.1);
                    player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 0.85F);
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.MUSIC, 0.8F, 1.6F);
                }
                player.sendMessage(Text.literal("Gratulujeme! Si posledný preživší. +100 bodov. Spolu: " + (winnerParticipant == null ? 100 : winnerParticipant.points)).formatted(Formatting.GREEN, Formatting.BOLD), false);
            }
        } else {
            broadcast(server, Text.literal("ICE RUSH sa skončilo bez víťaza.").formatted(Formatting.RED));
        }
        commitResults(server);
    }

    private void broadcastTitle(MinecraftServer server, String title, String subtitle, int stayTicks) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) sendTitle(player, title, subtitle, stayTicks);
        }
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) sendTitle(player, title, subtitle, stayTicks);
        }
    }

    private void sendTitle(ServerPlayerEntity player, String title, String subtitle) {
        sendTitle(player, title, subtitle, 60);
    }

    private void sendTitle(ServerPlayerEntity player, String title, String subtitle, int stayTicks) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, stayTicks, 5));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
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
            stats.roundsSurvived += participant.roundsSurvived;
            stats.speedRecord = Math.max(stats.speedRecord, participant.speedRecord);
            if (participant.bestParkingTicks > 0 &&
                (stats.bestParkingTicks == 0 || participant.bestParkingTicks < stats.bestParkingTicks)) {
                stats.bestParkingTicks = participant.bestParkingTicks;
            }
            if (participant.id.equals(winner)) {
                stats.wins++;
                unlockAchievement(stats, "Prvá výhra", stats.wins >= 1);
                ServerPlayerEntity winnerPlayer = server.getPlayerManager().getPlayer(winner);
                String winnerName = winnerPlayer == null ? (stats.playerName.isBlank() ? winner.toString().substring(0, 8) : stats.playerName) : winnerPlayer.getName().getString();
                statsStore.setLastWinner(winnerName);
            } else stats.losses++;
            unlockAchievement(stats, "Ice King", stats.wins >= 10);
            unlockAchievement(stats, "Turbo Master", stats.turboBonuses >= 25);
            unlockAchievement(stats, "Speed Demon", stats.speedRecord >= config.speedReference);
            unlockAchievement(stats, "Survivor", stats.roundsSurvived >= 10);
            unlockAchievement(stats, "Perfect Parking", stats.bestParkingTicks > 0 && stats.bestParkingTicks <= 100);
        }
        statsStore.save();
    }

    private void unlockAchievement(IceRushStatsStore.PlayerStats stats, String name, boolean condition) {
        if (condition && !stats.achievements.contains(name)) stats.achievements.add(name);
    }

    private void resetGame(MinecraftServer server, boolean adminReset) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            returnToLobby(player);
            player.sendMessage(Text.literal(adminReset
                ? "ICE RUSH bolo resetované administrátorom. Výsledky tohto zápasu sa neuložili."
                : "ICE RUSH sa skončilo. Lobby je znovu otvorené.").formatted(Formatting.AQUA), false);
        }
        participants.clear();
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
        celebrationTicks = 0;
    }

    private void teleportToLobby(ServerPlayerEntity player) {
        if (arenaWorld == null || lobbyPosition == null) return;
        player.teleport(arenaWorld, lobbyPosition.x, lobbyPosition.y, lobbyPosition.z, player.getYaw(), 0.0F);
    }

    private void returnToLobby(ServerPlayerEntity player) {
        player.stopRiding();
        teleportToLobby(player);
        boolean modeChanged = player.interactionManager.changeGameMode(GameMode.ADVENTURE);
        GameMode actualMode = player.interactionManager.getGameMode();
        IceRushMod.LOGGER.info("ICE RUSH lobby return for {}: changed={}, serverMode={}",
            player.getName().getString(), modeChanged, actualMode);
        sendLobbyMenu(player);
    }

    private void sendLobbyMenu(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("╔══════════════════════════════════╗").formatted(Formatting.AQUA, Formatting.BOLD), false);
        player.sendMessage(Text.literal("       TOSO  •  ICE RUSH").formatted(Formatting.AQUA, Formatting.BOLD), false);
        player.sendMessage(Text.literal("  Ľadová aréna  •  až 16 hráčov").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("╚══════════════════════════════════╝").formatted(Formatting.AQUA, Formatting.BOLD), false);
        player.sendMessage(Text.literal("  Vyber si akciu kliknutím na tlačidlo:").formatted(Formatting.GRAY), false);
        MutableText first = Text.empty();
        first.append(chatAction(" ▶ VSTÚPIŤ ", "/icerush join", Formatting.AQUA));
        first.append(Text.literal("  "));
        first.append(chatAction(" ✓ READY ", "/icerush ready", Formatting.GREEN));
        first.append(Text.literal("  "));
        first.append(chatAction(" 📘 PRAVIDLÁ ", "/icerush rules", Formatting.YELLOW));
        player.sendMessage(first, false);
        MutableText second = Text.empty();
        second.append(chatAction(" 🏆 TOP 10 ", "/icerush top", Formatting.GOLD));
        second.append(Text.literal("  "));
        second.append(chatAction(" 📊 ŠTATISTIKY ", "/icerush stats", Formatting.LIGHT_PURPLE));
        second.append(Text.literal("  "));
        second.append(chatAction(" 👑 VÍŤAZ ", "/icerush lastwinner", Formatting.WHITE));
        player.sendMessage(second, false);
        player.sendMessage(Text.literal("Tip: po vstupe stlač READY. Na štart treba 80 % pripravených hráčov.").formatted(Formatting.GRAY), false);
    }

    private MutableText chatAction(String label, String command, Formatting color) {
        return Text.literal(label).styled(style -> style.withColor(color).withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private int activeCount() {
        return (int) participants.values().stream().filter(p -> !p.eliminated).count();
    }

    private void broadcast(MinecraftServer server, Text message) {
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) player.sendMessage(message, false);
        }
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) player.sendMessage(message, false);
        }
    }
}
