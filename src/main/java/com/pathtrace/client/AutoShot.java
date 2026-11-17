package com.pathtrace.client;

import com.pathtrace.PathTraceMod;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Test mode for automated screenshots (used by CI), enabled with -Dpathtrace.autoshot=true.
 * Creates a flat creative world, builds a small test scene, takes screenshots with path tracing
 * on and off at day and at night, then quits.
 */
final class AutoShot {
    private static final long MAX_RUNTIME_MS = 25 * 60 * 1000L;

    static boolean requested() {
        return Boolean.getBoolean("pathtrace.autoshot");
    }

    private int stage;
    private int frames;
    private long stageStart;
    private final long started = System.currentTimeMillis();
    private BlockPos base;

    private void next(int s) {
        stage = s;
        frames = 0;
        stageStart = System.currentTimeMillis();
        PathTraceMod.LOG.info("[autoshot] stage " + s);
    }

    private boolean waited(int minFrames, long minMillis) {
        return frames >= minFrames && System.currentTimeMillis() - stageStart >= minMillis;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage != 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiMainMenu) {
            mc.gameSettings.renderDistanceChunks = 6;
            WorldSettings ws = new WorldSettings(1234L, GameType.CREATIVE, false, false, WorldType.FLAT);
            ws.enableCommands();
            next(1);
            mc.launchIntegratedServer("pathtrace_autoshot", "pathtrace_autoshot", ws);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        frames++;
        if (System.currentTimeMillis() - started > MAX_RUNTIME_MS) {
            PathTraceMod.LOG.error("[autoshot] timed out in stage " + stage);
            mc.shutdown();
            return;
        }
        switch (stage) {
            case 1: // in world: build the scene
                if (mc.player == null || mc.world == null || mc.getIntegratedServer() == null) return;
                if (!waited(20, 3000)) return;
                setupScene(mc.getIntegratedServer(), 2000L, false);
                mc.gameSettings.hideGUI = true;
                ClientHandler.setEnabled(true);
                next(2);
                break;
            case 2:
                if (!waited(80, 30000)) return;
                shot(mc, "1_day_pathtrace");
                ClientHandler.setEnabled(false);
                next(3);
                break;
            case 3:
                if (!waited(12, 3000)) return;
                shot(mc, "2_day_vanilla");
                setupScene(mc.getIntegratedServer(), 18000L, true);
                mc.gameSettings.hideGUI = false;   // show the hand holding a torch
                mc.player.inventory.currentItem = 0;
                ClientHandler.setEnabled(true);
                next(4);
                break;
            case 4:
                if (!waited(80, 15000)) return;
                shot(mc, "3_night_pathtrace");
                ClientHandler.setEnabled(false);
                next(5);
                break;
            case 5:
                if (!waited(12, 3000)) return;
                shot(mc, "4_night_vanilla");
                PathTraceMod.LOG.info("[autoshot] done");
                next(6);
                mc.shutdown();
                break;
            default:
                break;
        }
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        PathTraceMod.LOG.info("[autoshot] saved " + name);
    }

    private void setupScene(IntegratedServer server, long time, boolean night) {
        server.addScheduledTask(() -> {
            WorldServer w = server.getWorld(0);
            w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
            w.setWorldTime(time);
            if (base == null) {
                BlockPos spawn = w.getSpawnPoint();
                base = w.getHeight(spawn);
                build(w, base);
            }
            EntityPlayerMP p = server.getPlayerList().getPlayers().get(0);
            double px = base.getX() + 19.5, py = base.getY() + 1.0, pz = base.getZ() - 7.5;
            double tx = base.getX() + 5.0, ty = base.getY() + 1.5, tz = base.getZ() + 5.0;
            double dx = tx - px, dy = ty - (py + 1.62), dz = tz - pz;
            float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            p.capabilities.isFlying = true;
            p.sendPlayerAbilities();
            p.connection.setPlayerLocation(px, py, pz, yaw, pitch);
            p.inventory.setInventorySlotContents(0, night ? new ItemStack(Blocks.TORCH) : ItemStack.EMPTY);
            p.inventory.currentItem = 0;
        });
    }

    private static void build(WorldServer w, BlockPos b) {
        IBlockState planks = Blocks.PLANKS.getDefaultState();
        IBlockState cobble = Blocks.COBBLESTONE.getDefaultState();
        IBlockState stone = Blocks.STONEBRICK.getDefaultState();
        IBlockState log = Blocks.LOG.getDefaultState();
        IBlockState leaves = Blocks.LEAVES.getDefaultState();
        // open shed: back wall, side wall, roof, glowstone inside
        for (int x = 0; x <= 10; x++) for (int y = 0; y <= 3; y++) set(w, b, x, y, 8, planks);
        for (int z = 2; z <= 8; z++) for (int y = 0; y <= 3; y++) set(w, b, 0, y, z, planks);
        for (int x = -1; x <= 11; x++) for (int z = 1; z <= 8; z++) set(w, b, x, 4, z, planks);
        set(w, b, 5, 2, 7, Blocks.GLOWSTONE.getDefaultState());
        set(w, b, 8, 0, 3, Blocks.TORCH.getDefaultState());
        // pillars
        for (int y = 0; y <= 3; y++) set(w, b, 13, y, -1, cobble);
        for (int y = 0; y <= 1; y++) set(w, b, 15, y, -1, cobble);
        // low wall
        for (int x = 16; x <= 20; x++) for (int z = 3; z <= 5; z++) set(w, b, x, 0, z, stone);
        // tree
        for (int y = 0; y <= 4; y++) set(w, b, -5, y, 11, log);
        for (int x = -7; x <= -3; x++) for (int z = 9; z <= 13; z++) for (int y = 4; y <= 5; y++)
            if (x != -5 || z != 11 || y > 4) set(w, b, x, y, z, leaves);
        set(w, b, -5, 6, 11, leaves);
    }

    private static void set(WorldServer w, BlockPos b, int x, int y, int z, IBlockState s) {
        w.setBlockState(b.add(x, y, z), s, 3);
    }
}
