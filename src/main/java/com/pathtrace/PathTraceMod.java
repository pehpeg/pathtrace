package com.pathtrace;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = PathTraceMod.MODID,
        name = PathTraceMod.NAME,
        version = PathTraceMod.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]"
)
public class PathTraceMod {
    public static final String MODID = "pathtrace";
    public static final String NAME = "PathTrace";
    public static final String VERSION = "0.4.5";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() == Side.CLIENT) {
            com.pathtrace.client.ClientHandler.init();
        }
    }
}
