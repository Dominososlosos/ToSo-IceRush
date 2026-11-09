package sk.toso.icerush;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class IceRushGame {
    private enum State { WAITING, COUNTDOWN, RACING, PARKING, FINISHED }

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 16;
    private static final int COUNTDOWN_TICKS = 60;
    private static final int PARKING_TICKS = 400;
    private static final int FINISH_HOLD_TICKS = 160;
    private static final int MIN_RIDE_TICKS = 500;
    private static final int MAX_RIDE_TICKS = 800;
    private static final double PARKING_RADIUS_SQUARED = 6.25;

    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final List<Vec3d> parkingSpots = new ArrayList<>();
    private final Set<Integer> occupiedSpots = new HashSet<>();
    private final Set<UUID> lateSpectators = new HashSet<>();
    private State state = State.WAITING;
    private int timer;
    private int round;
    private UUID winner;

    private static final class Participant {
        private final UUID id;
        private boolean ready;
        private boolean eliminated;
        private boolean parked;
        private int parkingSpot = -1;
        private int points;

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
            .then(CommandManager.literal("start").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> start(ctx.getSource())))
            .then(CommandManager.literal("reset").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> reset(ctx.getSource())))
            .then(CommandManager.literal("addspot").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> addSpot(ctx.getSource())))
            .then(CommandManager.literal("clearspots").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> clearSpots(ctx.getSource())))
        );
    }

    private int join(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (state != State.WAITING) {
            player.changeGameMode(GameMode.SPECTATOR);
            lateSpectators.add(player.getUuid());
            player.sendMessage(Text.literal("Hra už prebieha. Pripojíš sa až do ďalšej hry.").formatted(Formatting.YELLOW), false);
            return 1;
        }
        if (participants.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("Už si v lobby.").formatted(Formatting.YELLOW), false);
            return 0;
        }
        if (participants.size() >= MAX_PLAYERS) {
            player.sendMessage(Text.literal("ICE RUSH je plný (16 hráčov).").formatted(Formatting.RED), false);
            return 0;
        }
        participants.put(player.getUuid(), new Participant(player.getUuid()));
        broadcast(source.getServer(), Text.literal(player.getName().getString() + " sa pripojil do ICE RUSH (" + participants.size() + "/" + MAX_PLAYERS + ").").formatted(Formatting.AQUA));
        return 1;
    }

    private int leave(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Počas hry už nemôžeš opustiť zostavu. Ak odídeš zo servera, budeš vyradený."));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Participant removed = participants.remove(player.getUuid());
        if (removed == null) {
            player.sendMessage(Text.literal("Nie si prihlásený do ICE RUSH.").formatted(Formatting.YELLOW), false);
            return 0;
        }
        broadcast(source.getServer(), Text.literal(player.getName().getString() + " opustil lobby.").formatted(Formatting.GRAY));
        return 1;
    }

    private int ready(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Participant participant = participants.get(player.getUuid());
        if (state != State.WAITING || participant == null) {
            player.sendMessage(Text.literal("Najprv sa prihlás do lobby: /icerush join").formatted(Formatting.YELLOW), false);
            return 0;
        }
        participant.ready = !participant.ready;
        broadcast(source.getServer(), Text.literal(player.getName().getString() + (participant.ready ? " je pripravený." : " už nie je pripravený.")).formatted(Formatting.AQUA));
        tryStartCountdown(source.getServer());
        return 1;
    }

    private int start(ServerCommandSource source) {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Hra už prebieha."));
            return 0;
        }
        if (participants.size() < MIN_PLAYERS) {
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

    private int clearSpots(ServerCommandSource source) {
        if (state != State.WAITING) {
            source.sendError(Text.literal("Parkovacie miesta možno meniť iba v lobby."));
            return 0;
        }
        parkingSpots.clear();
        source.sendFeedback(() -> Text.literal("Všetky parkovacie miesta boli odstránené."), false);
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
        if (state != State.WAITING && !participants.containsKey(player.getUuid())) {
            lateSpectators.add(player.getUuid());
            player.changeGameMode(GameMode.SPECTATOR);
            player.sendMessage(Text.literal("ICE RUSH už prebieha. Zatiaľ si divák; do lobby sa pripojíš po skončení hry.").formatted(Formatting.YELLOW), false);
        }
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (state == State.WAITING) {
            participants.remove(playerId);
        } else {
            Participant participant = participants.get(playerId);
            if (participant != null) participant.eliminated = true;
            lateSpectators.remove(playerId);
        }
    }

    private void tryStartCountdown(MinecraftServer server) {
        if (state != State.WAITING || participants.size() < MIN_PLAYERS) return;
        long readyCount = participants.values().stream().filter(p -> p.ready).count();
        int required = (int) Math.ceil(participants.size() * 0.80);
        if (readyCount >= required && parkingSpots.size() >= participants.size() - 1) beginCountdown(server);
    }

    private void beginCountdown(MinecraftServer server) {
        state = State.COUNTDOWN;
        timer = COUNTDOWN_TICKS;
        broadcast(server, Text.literal("ICE RUSH sa začína!").formatted(Formatting.GOLD, Formatting.BOLD));
    }

    public void tick(MinecraftServer server) {
        if (state == State.WAITING) return;
        if (state == State.COUNTDOWN) {
            timer--;
            if (timer == 40 || timer == 20 || timer == 0) {
                String label = timer == 40 ? "3" : timer == 20 ? "2" : "1";
                broadcast(server, Text.literal(label).formatted(Formatting.YELLOW, Formatting.BOLD));
            }
            if (timer <= 0) startRound(server);
            return;
        }
        if (state == State.RACING) {
            timer--;
            if (timer <= 0) beginParking(server);
            return;
        }
        if (state == State.PARKING) {
            updateParking(server);
            timer--;
            if (timer <= 0) finishParking(server);
            return;
        }
        if (state == State.FINISHED) {
            timer--;
            if (timer <= 0) resetGame(server, false);
        }
    }

    private void startRound(MinecraftServer server) {
        round++;
        for (Participant p : participants.values()) {
            p.parked = false;
            p.parkingSpot = -1;
            p.ready = false;
        }
        occupiedSpots.clear();
        state = State.RACING;
        timer = ThreadLocalRandom.current().nextInt(MIN_RIDE_TICKS, MAX_RIDE_TICKS + 1);
        broadcast(server, Text.literal("ŠTART! Kolo " + round + " — jazda!").formatted(Formatting.GREEN, Formatting.BOLD));
    }

    private void beginParking(MinecraftServer server) {
        state = State.PARKING;
        timer = PARKING_TICKS;
        occupiedSpots.clear();
        for (Participant p : participants.values()) { p.parked = false; p.parkingSpot = -1; }
        broadcast(server, Text.literal("PARKOVANIE! Máš 20 sekúnd na obsadenie miesta.").formatted(Formatting.GOLD, Formatting.BOLD));
    }

    private void updateParking(MinecraftServer server) {
        occupiedSpots.clear();
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            if (participant.parked) {
                if (participant.parkingSpot >= 0) occupiedSpots.add(participant.parkingSpot);
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player == null) continue;
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof BoatEntity)) continue;
            Vec3d boatPosition = vehicle.getPos();
            for (int i = 0; i < parkingSpots.size(); i++) {
                if (occupiedSpots.contains(i)) continue;
                if (boatPosition.squaredDistanceTo(parkingSpots.get(i)) <= PARKING_RADIUS_SQUARED) {
                    participant.parked = true;
                    participant.parkingSpot = i;
                    occupiedSpots.add(i);
                    player.sendMessage(Text.literal("Parkovacie miesto obsadené!").formatted(Formatting.GREEN), true);
                    break;
                }
            }
        }
    }

    private void finishParking(MinecraftServer server) {
        for (Participant participant : participants.values()) {
            if (participant.eliminated) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (participant.parked) {
                participant.points += 15;
                if (player != null) player.sendMessage(Text.literal("+15 bodov (parkovanie + prežitie kola). Spolu: " + participant.points).formatted(Formatting.GREEN), false);
            } else {
                int placeBeforeElimination = activeCount();
                if (placeBeforeElimination == 2) participant.points += 60;
                else if (placeBeforeElimination == 3) participant.points += 40;
                participant.eliminated = true;
                if (player != null) {
                    player.changeGameMode(GameMode.SPECTATOR);
                    player.sendMessage(Text.literal("VYPADÁVAŠ! Nenašiel/nenašla si parkovacie miesto. Body: " + participant.points).formatted(Formatting.RED, Formatting.BOLD), false);
                }
            }
        }

        int remaining = activeCount();
        if (remaining <= 1) {
            declareWinner(server);
        } else {
            for (Participant p : participants.values()) { p.parked = false; p.parkingSpot = -1; }
            state = State.RACING;
            timer = ThreadLocalRandom.current().nextInt(MIN_RIDE_TICKS, MAX_RIDE_TICKS + 1);
            broadcast(server, Text.literal("Kolo " + (round + 1) + " začína! Zostáva hráčov: " + remaining).formatted(Formatting.AQUA, Formatting.BOLD));
        }
    }

    private void declareWinner(MinecraftServer server) {
        winner = participants.values().stream().filter(p -> !p.eliminated).map(p -> p.id).findFirst().orElse(null);
        state = State.FINISHED;
        timer = FINISH_HOLD_TICKS;
        if (winner != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(winner);
            String name = player == null ? "Víťaz" : player.getName().getString();
            broadcast(server, Text.literal("🏆 " + name + " VYHRÁVA ICE RUSH!").formatted(Formatting.GOLD, Formatting.BOLD));
            Participant winnerParticipant = participants.get(winner);\n            if (winnerParticipant != null) winnerParticipant.points += 100;\n            if (player != null) player.sendMessage(Text.literal("Gratulujeme! Si posledný preživší. +100 bodov. Spolu: " + (winnerParticipant == null ? 100 : winnerParticipant.points)).formatted(Formatting.GREEN, Formatting.BOLD), false);
        } else {
            broadcast(server, Text.literal("ICE RUSH sa skončilo bez víťaza.").formatted(Formatting.RED));
        }
    }

    private void resetGame(MinecraftServer server, boolean adminReset) {
        for (Participant participant : participants.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(participant.id);
            if (player != null) player.changeGameMode(GameMode.ADVENTURE);
        }
        participants.clear();
        for (UUID id : lateSpectators) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) player.changeGameMode(GameMode.ADVENTURE);
        }
        lateSpectators.clear();
        occupiedSpots.clear();
        state = State.WAITING;
        timer = 0;
        round = 0;
        winner = null;
        if (!adminReset) broadcast(server, Text.literal("ICE RUSH sa skončilo. Lobby je znovu otvorené.").formatted(Formatting.AQUA));
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
