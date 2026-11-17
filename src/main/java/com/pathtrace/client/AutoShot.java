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
        boolean lod = true, bloom = true;
        Shot(String name, long time, boolean torch, boolean enabled, boolean hud, int debug, double[] cam, double[] target) {
            this.name = name; this.time = time; this.torch = torch; this.enabled = enabled; this.hud = hud;
            this.debug = debug; this.cam = cam; this.target = target;
        }
        Shot noLod() {
            lod = false;
            return this;
        }
        Shot noBloom() {
            bloom = false;
            return this;
        }
    }

    // camera positions relative to the scene origin (feet position) and look-at targets
    private static final double[] OUTSIDE = {19.5, 1.0, -7.5}, OUTSIDE_T = {5.0, 1.5, 5.0};
    private static final double[] INSIDE = {9.5, 0.0, 2.5}, INSIDE_T = {5.5, 2.5, 7.5};
    private static final double[] ABOVE = {20.0, 8.0, -2.0}, ABOVE_T = {4.0, 4.0, 5.0};
    private static final double[] GROUND = {18.5, 0.0, -1.5}, GROUND_T = {15.0, 0.0, 3.0};
    // a lit tower ~85 blocks away: outside the 128-block voxel grid, inside render distance 8
    private static final double[] FAR = {16.0, 0.0, 10.0}, FAR_T = {2.0, 8.0, 89.0};
    // raised, ~90 blocks from the tower, looking at the ground west of it where its shadow falls
    private static final double[] FAR_HIGH = {12.0, 24.0, 2.0}, FAR_HIGH_T = {-14.0, 0.0, 90.0};
    // non-full blocks (slab, stairs, fence, iron bars, sugar cane) and a placed torch
    private static final double[] PARTIAL = {20.5, 2.0, 19.5}, PARTIAL_T = {20.0, 0.0, 10.0};
    // glowstone set flush into the floor and a lava pool
    private static final double[] FLUSH = {27.5, 2.0, 23.5}, FLUSH_T = {28.0, -1.0, 15.0};
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
            new Shot("13_night_debug_direct", NIGHT, true, true, false, 1, OUTSIDE, OUTSIDE_T),
            new Shot("14_night_torch_ground_pathtrace", NIGHT, true, true, true, 0, GROUND, GROUND_T),
            new Shot("15_night_torch_ground_vanilla", NIGHT, true, false, true, 0, GROUND, GROUND_T),
            new Shot("16_night_torch_ground_debug_direct", NIGHT, true, true, false, 1, GROUND, GROUND_T),
            new Shot("17_night_far_tower_pathtrace", NIGHT, false, true, false, 0, FAR, FAR_T),
            new Shot("18_night_far_tower_vanilla", NIGHT, false, false, false, 0, FAR, FAR_T),
            new Shot("19_night_far_tower_debug_direct", NIGHT, false, true, false, 1, FAR, FAR_T),
            new Shot("20_day_far_tower_pathtrace", DAY, false, true, false, 0, FAR, FAR_T),
            new Shot("21_day_far_tower_vanilla", DAY, false, false, false, 0, FAR, FAR_T),
            new Shot("22_day_far_tower_debug_direct", DAY, false, true, false, 1, FAR, FAR_T),
            new Shot("23_day_far_shadow_lod", DAY, false, true, false, 0, FAR_HIGH, FAR_HIGH_T),
            new Shot("24_day_far_shadow_no_lod", DAY, false, true, false, 0, FAR_HIGH, FAR_HIGH_T).noLod(),
            new Shot("25_day_far_shadow_vanilla", DAY, false, false, false, 0, FAR_HIGH, FAR_HIGH_T),
            new Shot("26_day_partial_pathtrace", DAY, false, true, false, 0, PARTIAL, PARTIAL_T),
            new Shot("27_day_partial_vanilla", DAY, false, false, false, 0, PARTIAL, PARTIAL_T),
            new Shot("28_night_partial_pathtrace", NIGHT, false, true, false, 0, PARTIAL, PARTIAL_T),
            new Shot("29_night_partial_vanilla", NIGHT, false, false, false, 0, PARTIAL, PARTIAL_T),
            new Shot("30_night_flush_lava_pathtrace", NIGHT, false, true, false, 0, FLUSH, FLUSH_T),
            new Shot("31_night_flush_lava_no_bloom", NIGHT, false, true, false, 0, FLUSH, FLUSH_T).noBloom(),
            new Shot("32_night_flush_lava_vanilla", NIGHT, false, false, false, 0, FLUSH, FLUSH_T),
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
            mc.gameSettings.renderDistanceChunks = 8;
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
        PTConfig.lodShadows = shot.lod;
        PTConfig.bloom = shot.bloom;
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
        // distant tower: stone column with glowstone windows, torches around its foot
        for (int y = 0; y <= 20; y++)
            for (int x = 0; x <= 4; x++)
                for (int z = 88; z <= 92; z++) set(w, b, x, y, z, stone);
        for (int y = 2; y <= 18; y += 4)
            for (int x = 1; x <= 3; x += 2) set(w, b, x, y, 88, Blocks.GLOWSTONE.getDefaultState());
        for (int x = -3; x <= 7; x += 2) set(w, b, x, 0, 85, Blocks.TORCH.getDefaultState());
        // non-full blocks
        set(w, b, 16, 0, 9, Blocks.WOODEN_SLAB.getDefaultState());
        set(w, b, 17, 0, 9, Blocks.OAK_STAIRS.getDefaultState());
        for (int z = 9; z <= 11; z++) set(w, b, 19, 0, z, Blocks.OAK_FENCE.getDefaultState());
        for (int y = 0; y <= 2; y++) for (int z = 9; z <= 11; z++) set(w, b, 21, y, z, Blocks.IRON_BARS.getDefaultState());
        set(w, b, 23, -1, 10, Blocks.WATER.getDefaultState());
        for (int y = 0; y <= 2; y++) set(w, b, 23, y, 9, Blocks.REEDS.getDefaultState());
        set(w, b, 20, 0, 13, Blocks.TORCH.getDefaultState());
        // light sources flush with the floor
        set(w, b, 26, -1, 16, Blocks.GLOWSTONE.getDefaultState());
        for (int x = 28; x <= 30; x++) for (int z = 14; z <= 16; z++) set(w, b, x, -1, z, Blocks.LAVA.getDefaultState());
    }

    private static void set(WorldServer w, BlockPos b, int x, int y, int z, IBlockState s) {
        w.setBlockState(b.add(x, y, z), s, 3);
    }
}
