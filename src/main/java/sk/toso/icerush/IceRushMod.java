package sk.toso.icerush;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
        LOGGER.info("ICE RUSH initialized for Fabric Loader 0.19.1 / Minecraft 1.21.1");
    }
}
