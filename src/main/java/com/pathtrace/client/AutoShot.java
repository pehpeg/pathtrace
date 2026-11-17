package com.pathtrace.client;

import com.pathtrace.PTConfig;
import com.pathtrace.PathTraceMod;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.tutorial.TutorialSteps;
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
 * Creates a flat creative world, builds a small test scene, takes a list of screenshots
 * (path tracing on/off, debug views, day/night, several angles), then quits.
 */
final class AutoShot {
    private static final long MAX_RUNTIME_MS = 25 * 60 * 1000L;

    static boolean requested() {
        return Boolean.getBoolean("pathtrace.autoshot");
    }

    /** One screenshot: when, where from, and with which settings. */
    private static final class Shot {
        final String name; final long time; final boolean torch, enabled, hud;
        final int debug; final double[] cam, target;
        Shot(String name, long time, boolean torch, boolean enabled, boolean hud, int debug, double[] cam, double[] target) {
            this.name = name; this.time = time; this.torch = torch; this.enabled = enabled; this.hud = hud;
            this.debug = debug; this.cam = cam; this.target = target;
        }
    }

    // camera positions relative to the scene origin (feet position) and look-at targets
    private static final double[] OUTSIDE = {19.5, 1.0, -7.5}, OUTSIDE_T = {5.0, 1.5, 5.0};
    private static final double[] INSIDE = {9.5, 0.0, 2.5}, INSIDE_T = {5.5, 2.5, 7.5};
    private static final double[] ABOVE = {20.0, 8.0, -2.0}, ABOVE_T = {4.0, 4.0, 5.0};
    private static final long DAY = 2000L, NIGHT = 18000L;

    private static final Shot[] SHOTS = {
            new Shot("01_day_pathtrace", DAY, false, true, false, 0, OUTSIDE, OUTSIDE_T),
            new Shot("02_day_vanilla", DAY, false, false, false, 0, OUTSIDE, OUTSIDE_T),
            new Shot("03_day_debug_direct", DAY, false, true, false, 1, OUTSIDE, OUTSIDE_T),
            new Shot("04_day_debug_bounce", DAY, false, true, false, 2, OUTSIDE, OUTSIDE_T),
            new Shot("05_day_debug_normals", DAY, false, true, false, 3, OUTSIDE, OUTSIDE_T),
            new Shot("06_day_roof_pathtrace", DAY, false, true, false, 0, ABOVE, ABOVE_T),
            new Shot("07_day_inside_pathtrace", DAY, false, true, false, 0, INSIDE, INSIDE_T),
            new Shot("08_day_inside_vanilla", DAY, false, false, false, 0, INSIDE, INSIDE_T),
            new Shot("09_night_pathtrace", NIGHT, true, true, true, 0, OUTSIDE, OUTSIDE_T),
            new Shot("10_night_vanilla", NIGHT, true, false, true, 0, OUTSIDE, OUTSIDE_T),
            new Shot("11_night_inside_pathtrace", NIGHT, true, true, true, 0, INSIDE, INSIDE_T),
            new Shot("12_night_inside_vanilla", NIGHT, true, false, true, 0, INSIDE, INSIDE_T),
    };

    private int index = -1;          // -1 = not in a world yet
    private boolean started;
    private int frames;
    private long stepStart;
    private final long launched = System.currentTimeMillis();
    private BlockPos base;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || started) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiMainMenu) {
            started = true;
            mc.gameSettings.renderDistanceChunks = 6;
            mc.gameSettings.pauseOnLostFocus = false;   // the virtual display never has focus
            mc.gameSettings.tutorialStep = TutorialSteps.NONE;
            mc.getTutorial().setStep(TutorialSteps.NONE);
            WorldSettings ws = new WorldSettings(1234L, GameType.CREATIVE, false, false, WorldType.FLAT);
            ws.enableCommands();
            PathTraceMod.LOG.info("[autoshot] creating world");
            mc.launchIntegratedServer("pathtrace_autoshot", "pathtrace_autoshot", ws);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !started) return;
        Minecraft mc = Minecraft.getMinecraft();
        frames++;
        if (System.currentTimeMillis() - launched > MAX_RUNTIME_MS) {
            PathTraceMod.LOG.error("[autoshot] timed out at shot " + index);
            mc.shutdown();
            return;
        }
        if (mc.world == null || mc.player == null || mc.getIntegratedServer() == null) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);   // close pause menus etc.

        if (index < 0) {
            if (frames < 20) return;
            begin(mc, 0);
            return;
        }
        if (index >= SHOTS.length) return;
        Shot shot = SHOTS[index];
        // first shot of a scene/time needs longer for chunks, the voxel grid and accumulation to settle
        boolean fresh = index == 0 || SHOTS[index - 1].time != shot.time || SHOTS[index - 1].cam != shot.cam;
        int minFrames = shot.enabled ? (fresh ? 90 : 50) : 15;
        long minMillis = index == 0 ? 25000 : (fresh ? 8000 : 2000);
        if (frames < minFrames || System.currentTimeMillis() - stepStart < minMillis) return;

        ScreenShotHelper.saveScreenshot(mc.gameDir, shot.name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        PathTraceMod.LOG.info("[autoshot] saved " + shot.name);
        if (index + 1 < SHOTS.length) {
            begin(mc, index + 1);
        } else {
            index = SHOTS.length;
            PTConfig.debugView = 0;
            PathTraceMod.LOG.info("[autoshot] done");
            mc.shutdown();
        }
    }

    private void begin(Minecraft mc, int i) {
        Shot prev = i > 0 ? SHOTS[i - 1] : null;
        Shot shot = SHOTS[i];
        index = i;
        frames = 0;
        stepStart = System.currentTimeMillis();
        mc.gameSettings.hideGUI = !shot.hud;
        PTConfig.debugView = shot.debug;
        ClientHandler.setEnabled(shot.enabled);
        if (prev == null || prev.time != shot.time || prev.cam != shot.cam || prev.torch != shot.torch) {
            setupScene(mc.getIntegratedServer(), shot);
            mc.player.inventory.currentItem = 0;
        }
        PathTraceMod.LOG.info("[autoshot] shot " + i + ": " + shot.name);
    }

    private void setupScene(IntegratedServer server, Shot shot) {
        server.addScheduledTask(() -> {
            WorldServer w = server.getWorld(0);
            w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
            w.setWorldTime(shot.time);
            if (base == null) {
                base = w.getHeight(w.getSpawnPoint());
                build(w, base);
            }
            EntityPlayerMP p = server.getPlayerList().getPlayers().get(0);
            double px = base.getX() + shot.cam[0], py = base.getY() + shot.cam[1], pz = base.getZ() + shot.cam[2];
            double tx = base.getX() + shot.target[0], ty = base.getY() + shot.target[1], tz = base.getZ() + shot.target[2];
            double dx = tx - px, dy = ty - (py + 1.62), dz = tz - pz;
            float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            p.capabilities.isFlying = true;
            p.sendPlayerAbilities();
            p.connection.setPlayerLocation(px, py, pz, yaw, pitch);
            p.inventory.setInventorySlotContents(0, shot.torch ? new ItemStack(Blocks.TORCH) : ItemStack.EMPTY);
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
