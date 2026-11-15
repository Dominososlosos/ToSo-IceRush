package sk.toso.icerush;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IceRushMod implements ModInitializer {
    public static final String MOD_ID = "icerush";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        IceRushGame game = new IceRushGame();
        CommandRegistrationCallback.EVENT.register(game::registerCommands);
        ServerTickEvents.END_SERVER_TICK.register(game::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(game::onServerStarted);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> game.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> game.onPlayerDisconnect(handler.player.getUuid(), server));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Command tags are server-side data, so the client identifies the button by entity type.
            if (world.isClient) return entity instanceof InteractionEntity ? ActionResult.SUCCESS : ActionResult.PASS;
            if (!game.isLobbyButton(entity)) return ActionResult.PASS;
            if (player instanceof ServerPlayerEntity serverPlayer) game.handleLobbyButton(serverPlayer, entity);
            return ActionResult.SUCCESS;
        });
        LOGGER.info("ICE RUSH initialized for Fabric Loader 0.19.1 / Minecraft 1.21.1");
    }
}
