package com.pathtrace.client;

import com.pathtrace.PathTraceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

public final class ClientHandler {
    private static final KeyBinding TOGGLE =
            new KeyBinding("key.pathtrace.toggle", Keyboard.KEY_NONE, "key.categories.pathtrace");   // use /pathtrace instead
    private static final KeyBinding RELOAD =
            new KeyBinding("key.pathtrace.reload", Keyboard.KEY_NONE, "key.categories.pathtrace");

    private static final PathTraceRenderer RENDERER = new PathTraceRenderer();
    private static boolean enabled = true;

    public static void init() {
        ClientRegistry.registerKeyBinding(TOGGLE);
        ClientRegistry.registerKeyBinding(RELOAD);
        MinecraftForge.EVENT_BUS.register(new ClientHandler());
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new PathTraceCommand());
        if (AutoShot.requested()) {
            PathTraceMod.LOG.info("PathTrace autoshot test mode enabled");
            MinecraftForge.EVENT_BUS.register(new AutoShot());
        }
        PathTraceMod.LOG.info("PathTrace loaded. Use /pathtrace on|off|toggle|status|reload|set.");
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void reloadShaders() {
        RENDERER.reloadShaders();
    }

    static void resetHistory() {
        RENDERER.resetHistory();
    }

    static void setEnabled(boolean on) {
        if (enabled != on) RENDERER.resetHistory();
        enabled = on;
    }

    static String perfReport() {
        return RENDERER.perf.report();
    }

    static void perfReset() {
        RENDERER.perf.reset();
    }

    // ---- "PathTrace..." button on the pause menu, next to other mods' corner buttons
    private static final int MENU_BUTTON_ID = 0x50540;

    @SubscribeEvent
    public void onInitGui(net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.getGui() instanceof net.minecraft.client.gui.GuiIngameMenu)) return;
        java.util.List<net.minecraft.client.gui.GuiButton> buttons = event.getButtonList();
        int w = 100, h = 20, x = 5, y = 5;
        // first free slot going down the left edge (other mods put buttons in the corners too)
        for (int tries = 0; tries < 8; tries++) {
            boolean free = true;
            for (net.minecraft.client.gui.GuiButton b : buttons) {
                if (b.x < x + w && b.x + b.getButtonWidth() > x && b.y < y + h && b.y + 20 > y) { free = false; break; }
            }
            if (free) break;
            y += h + 4;
        }
        buttons.add(new net.minecraft.client.gui.GuiButton(MENU_BUTTON_ID, x, y, w, h, "PathTrace..."));
    }

    @SubscribeEvent
    public void onMenuButton(net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.getGui() instanceof net.minecraft.client.gui.GuiIngameMenu) || event.getButton().id != MENU_BUTTON_ID) return;
        event.setCanceled(true);
        Minecraft.getMinecraft().displayGuiScreen(new net.minecraftforge.fml.client.config.GuiConfig(event.getGui(),
                net.minecraftforge.common.config.ConfigElement.from(com.pathtrace.PTConfig.class).getChildElements(),
                PathTraceMod.MODID, false, false, "PathTrace settings"));
    }

    static void chat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) mc.player.sendStatusMessage(new TextComponentString(msg), true);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            RENDERER.resetHistory();
            chat("PathTrace " + (enabled ? "§aON" : "§cOFF"));
        }
        if (RELOAD.isPressed()) {
            RENDERER.reloadShaders();
            chat("PathTrace: shaders reloaded");
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        // Draw the world with a flat white lightmap so the frame holds plain surface colours;
        // the path tracer supplies all the lighting.
        Fullbright.setActive(enabled && mc.world != null && RENDERER.isReady());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (enabled) RENDERER.render(event.getPartialTicks());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderHand(RenderHandEvent event) {
        if (!enabled) return;
        if (RENDERER.renderHand(event.getPartialTicks(), event.getRenderPass())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld().isRemote) {
            event.getWorld().addEventListener(new BlockChangeListener(event.getWorld(), RENDERER.volume(), RENDERER.emitters()));
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) RENDERER.onWorldChange();
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (PathTraceMod.MODID.equals(event.getModID())) {
            ConfigManager.sync(PathTraceMod.MODID, Config.Type.INSTANCE);
            RENDERER.resetHistory();
        }
    }
}
