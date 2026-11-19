package com.pathtrace.client;

import com.pathtrace.PTConfig;
import com.pathtrace.PathTraceMod;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockWallSign;
import net.minecraft.util.EnumFacing;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
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
        boolean fast;
        Shot fast() {
            fast = true;
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
    // close-up of the fence / iron bars bases (shadows inside non-full blocks' own spaces)
    private static final double[] CLOSE = {20.0, 0.5, 15.0}, CLOSE_T = {20.0, 0.0, 10.0};
    private static final double[] TOP = {20.0, 7.0, 12.0}, TOP_T = {20.0, 0.0, 11.0};
    // torch boxed in by green / red / blue stained glass
    private static final double[] GLASS = {31.5, 3.0, -1.5}, GLASS_T = {31.0, 0.0, 3.5};
    // torch + pillar ~70 blocks away (outside the detailed grid)
    private static final double[] FAR_POINT = {-6.0, 16.0, 10.0}, FAR_POINT_T = {-6.0, 0.0, 80.0};
    // double chest with a wall torch above, wall + standing signs
    private static final double[] CHEST = {38.0, 0.3, 17.0}, CHEST_T = {38.0, 0.8, 21.5};
    // room lined with glowstone (many lights close together), with a pillar inside
    private static final double[] GLOWROOM = {43.5, 0.0, 3.5}, GLOWROOM_T = {46.5, 1.0, 7.5};
    private static final long DAY = 2000L, NIGHT = 18000L;

    // a big stone-brick hall like a real base: glowstone set into the walls every few blocks,
    // a glowstone strip over a row of wall signs, chests sunk into the floor, a long corridor
    private static final double[] HALL = {63.0, 0.0, 12.0}, HALL_T = {72.0, 0.5, 22.0};
    private static final double[] PIT = {71.0, 0.0, 18.5}, PIT_T = {71.0, -0.8, 21.5};
    private static final double[] SIGNS = {68.0, 0.0, 19.0}, SIGNS_T = {68.0, 2.5, 23.0};
    private static final double[] HALLWAY = {124.5, 0.0, 2.5}, HALLWAY_T = {124.5, 0.3, 40.0};
    private static final double[] PATHROOM = {137.0, 1.2, 1.5}, PATHROOM_T = {139.5, -0.5, 6.0};
    private static final double[] CLOUDS = {0.0, 0.0, 0.0}, CLOUDS_T = {40.0, 30.0, 40.0};
    private static final double[] CORRIDOR = {85.5, 0.0, 12.5}, CORRIDOR_T = {110.0, 1.0, 12.5};

    /** While iterating on one problem: only take the shots from FOCUS_FROM on. */
    private static final boolean FOCUS = true;
    private static final String FOCUS_FROM = "77";

    private static final Shot[] SHOTS = filter(new Shot[] {
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
            new Shot("33_day_partial_closeup", DAY, false, true, false, 0, CLOSE, CLOSE_T),
            new Shot("34_night_partial_closeup", NIGHT, false, true, false, 0, CLOSE, CLOSE_T),
            new Shot("35_night_partial_closeup_vanilla", NIGHT, false, false, false, 0, CLOSE, CLOSE_T),
            new Shot("36_day_partial_top_debug_direct", DAY, false, true, false, 1, TOP, TOP_T),
            new Shot("37_night_partial_top_debug_direct", NIGHT, false, true, false, 1, TOP, TOP_T),
            new Shot("38_day_partial_top_normals", DAY, false, true, false, 3, TOP, TOP_T),
            new Shot("39_night_glass_pathtrace", NIGHT, false, true, false, 0, GLASS, GLASS_T),
            new Shot("40_night_glass_vanilla", NIGHT, false, false, false, 0, GLASS, GLASS_T),
            new Shot("41_night_far_point_shadow", NIGHT, false, true, false, 0, FAR_POINT, FAR_POINT_T),
            new Shot("42_night_far_point_shadow_no_lod", NIGHT, false, true, false, 0, FAR_POINT, FAR_POINT_T).noLod(),
            new Shot("43_night_far_point_shadow_vanilla", NIGHT, false, false, false, 0, FAR_POINT, FAR_POINT_T),
            new Shot("44_night_chest_pathtrace", NIGHT, false, true, false, 0, CHEST, CHEST_T),
            new Shot("45_night_chest_vanilla", NIGHT, false, false, false, 0, CHEST, CHEST_T),
            new Shot("46_night_chest_debug_direct", NIGHT, false, true, false, 1, CHEST, CHEST_T),
            new Shot("47_night_chest_debug_bounce", NIGHT, false, true, false, 2, CHEST, CHEST_T),
            new Shot("48_night_glowroom_pathtrace", NIGHT, false, true, false, 0, GLOWROOM, GLOWROOM_T),
            new Shot("49_night_glowroom_vanilla", NIGHT, false, false, false, 0, GLOWROOM, GLOWROOM_T),
            new Shot("50_night_glowroom_debug_direct", NIGHT, false, true, false, 1, GLOWROOM, GLOWROOM_T),
            new Shot("51_night_glowroom_debug_bounce", NIGHT, false, true, false, 2, GLOWROOM, GLOWROOM_T),
            new Shot("52_hall_pathtrace", NIGHT, true, true, false, 0, HALL, HALL_T),
            new Shot("53_hall_vanilla", NIGHT, true, false, false, 0, HALL, HALL_T),
            new Shot("54_hall_debug_direct", NIGHT, true, true, false, 1, HALL, HALL_T),
            new Shot("55_hall_debug_bounce", NIGHT, true, true, false, 2, HALL, HALL_T),
            new Shot("56_hall_debug_lightcount", NIGHT, true, true, false, 5, HALL, HALL_T),
            new Shot("57_pit_pathtrace", NIGHT, true, true, false, 0, PIT, PIT_T),
            new Shot("58_pit_vanilla", NIGHT, true, false, false, 0, PIT, PIT_T),
            new Shot("59_pit_debug_direct", NIGHT, true, true, false, 1, PIT, PIT_T),
            new Shot("60_pit_debug_bounce", NIGHT, true, true, false, 2, PIT, PIT_T),
            new Shot("61_pit_debug_rays", NIGHT, true, true, false, 4, PIT, PIT_T),
            new Shot("62_signs_pathtrace", NIGHT, true, true, false, 0, SIGNS, SIGNS_T),
            new Shot("63_signs_vanilla", NIGHT, true, false, false, 0, SIGNS, SIGNS_T),
            new Shot("64_signs_debug_direct", NIGHT, true, true, false, 1, SIGNS, SIGNS_T),
            new Shot("65_corridor_pathtrace", NIGHT, true, true, false, 0, CORRIDOR, CORRIDOR_T),
            new Shot("66_corridor_vanilla", NIGHT, true, false, false, 0, CORRIDOR, CORRIDOR_T),
            new Shot("67_corridor_debug_direct", NIGHT, true, true, false, 1, CORRIDOR, CORRIDOR_T),
            new Shot("68_corridor_debug_lightcount", NIGHT, true, true, false, 5, CORRIDOR, CORRIDOR_T),
            new Shot("69_day_clouds_pathtrace", DAY, false, true, false, 0, CLOUDS, CLOUDS_T),
            new Shot("70_day_clouds_vanilla", DAY, false, false, false, 0, CLOUDS, CLOUDS_T),
            new Shot("71_day_clouds_debug_mask", DAY, false, true, false, 6, CLOUDS, CLOUDS_T),
            new Shot("72_day_clouds_debug_scene", DAY, false, true, false, 7, CLOUDS, CLOUDS_T),
            new Shot("73_hallway_pathtrace", NIGHT, false, true, false, 0, HALLWAY, HALLWAY_T),
            new Shot("74_hallway_vanilla", NIGHT, false, false, false, 0, HALLWAY, HALLWAY_T),
            new Shot("75_hallway_debug_direct", NIGHT, false, true, false, 1, HALLWAY, HALLWAY_T),
            new Shot("76_hallway_debug_bounce", NIGHT, false, true, false, 2, HALLWAY, HALLWAY_T),
            new Shot("77_pathfloor_pathtrace", NIGHT, false, true, false, 0, PATHROOM, PATHROOM_T),
            new Shot("78_pathfloor_vanilla", NIGHT, false, false, false, 0, PATHROOM, PATHROOM_T),
            new Shot("79_pathfloor_debug_direct", NIGHT, false, true, false, 1, PATHROOM, PATHROOM_T),
            new Shot("80_hallway_fast", NIGHT, false, true, false, 0, HALLWAY, HALLWAY_T).fast(),
            new Shot("81_hallway_fast_direct", NIGHT, false, true, false, 1, HALLWAY, HALLWAY_T).fast(),
            new Shot("82_hallway_fast_bounce", NIGHT, false, true, false, 2, HALLWAY, HALLWAY_T).fast(),
            new Shot("83_hall_fast", NIGHT, true, true, false, 0, HALL, HALL_T).fast(),
            new Shot("84_hall_fast_bounce", NIGHT, true, true, false, 2, HALL, HALL_T).fast(),
            new Shot("85_hall_balanced_bounce", NIGHT, true, true, false, 2, HALL, HALL_T),
    });

    private static Shot[] filter(Shot[] all) {
        if (!FOCUS) return all;
        java.util.List<Shot> keep = new java.util.ArrayList<>();
        for (Shot s : all) if (s.name.compareTo(FOCUS_FROM) >= 0) keep.add(s);
        return keep.toArray(new Shot[0]);
    }


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
        if (shot.enabled) PathTraceMod.LOG.info("[autoshot] perf " + shot.name + ": " + ClientHandler.perfReport().replace('\n', ' '));
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
        if (shot.fast) { PTConfig.renderScale = 0.33; PTConfig.directScale = 0.33; PTConfig.shadowedLights = 1; PTConfig.lightSamples = 1; }
        else { PTConfig.renderScale = 0.5; PTConfig.directScale = 0.5; PTConfig.shadowedLights = 3; PTConfig.lightSamples = 2; }
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
        // torch in a box of stained glass (open towards the camera)
        set(w, b, 31, 0, 3, Blocks.TORCH.getDefaultState());
        for (int y = 0; y <= 1; y++) {
            for (int z = 3; z <= 4; z++) {
                set(w, b, 30, y, z, glass(EnumDyeColor.LIME));
                set(w, b, 32, y, z, glass(EnumDyeColor.LIGHT_BLUE));
            }
            set(w, b, 31, y, 4, glass(EnumDyeColor.RED));
        }
        // double chest against a wall, wall torch above it, wall sign and standing sign
        for (int x = 36; x <= 39; x++) for (int y = 0; y <= 2; y++) set(w, b, x, y, 22, Blocks.COBBLESTONE.getDefaultState());
        set(w, b, 37, 0, 21, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.SOUTH));
        set(w, b, 38, 0, 21, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.SOUTH));
        set(w, b, 37, 1, 21, Blocks.TORCH.getDefaultState().withProperty(BlockTorch.FACING, EnumFacing.SOUTH));
        set(w, b, 38, 1, 21, Blocks.WALL_SIGN.getDefaultState().withProperty(BlockWallSign.FACING, EnumFacing.SOUTH));
        set(w, b, 40, 0, 20, Blocks.STANDING_SIGN.getDefaultState());
        // room lined with glowstone (checkerboard walls), a door and a pillar inside
        for (int x = 42; x <= 48; x++)
            for (int z = 2; z <= 8; z++)
                for (int y = 0; y <= 4; y++) {
                    boolean wall = x == 42 || x == 48 || z == 2 || z == 8 || y == 4;
                    if (!wall) continue;
                    boolean glow = ((x + y + z) & 1) == 0;
                    set(w, b, x, y, z, glow ? Blocks.GLOWSTONE.getDefaultState() : Blocks.STONEBRICK.getDefaultState());
                }
        set(w, b, 43, 0, 2, Blocks.AIR.getDefaultState());
        set(w, b, 43, 1, 2, Blocks.AIR.getDefaultState());
        for (int y = 0; y <= 3; y++) set(w, b, 45, y, 5, Blocks.STONEBRICK.getDefaultState());
        buildHall(w, b);
        // a small room with a grass-path floor (15/16 tall) and one glowstone low in the wall
        for (int x = 134; x <= 142; x++)
            for (int z = 0; z <= 8; z++)
                for (int y = -1; y <= 4; y++) {
                    boolean shell = x == 134 || x == 142 || z == 0 || z == 8 || y == 4;
                    IBlockState st = y == -1 ? Blocks.GRASS_PATH.getDefaultState()
                            : shell ? Blocks.STONEBRICK.getDefaultState() : Blocks.AIR.getDefaultState();
                    set(w, b, x, y, z, st);
                }
        set(w, b, 139, 0, 8, Blocks.GLOWSTONE.getDefaultState());
        // a 7-wide hallway, glowstone in both walls at floor level and higher up
        for (int x = 120; x <= 128; x++)
            for (int z = 0; z <= 44; z++)
                for (int y = -1; y <= 6; y++) {
                    boolean wall = x == 120 || x == 128 || y == 6 || y == -1 || z == 44;
                    if (!wall) { set(w, b, x, y, z, Blocks.AIR.getDefaultState()); continue; }
                    boolean glow = (x == 120 || x == 128) && (y == 0 || y == 3) && (z % 4 == (y == 0 ? 1 : 3));
                    set(w, b, x, y, z, glow ? Blocks.GLOWSTONE.getDefaultState()
                            : y == -1 ? Blocks.PLANKS.getDefaultState() : Blocks.STONEBRICK.getDefaultState());
                }
        // far away: a torch with a pillar next to it
        set(w, b, -8, 0, 80, Blocks.TORCH.getDefaultState());
        for (int y = 0; y <= 2; y++) set(w, b, -6, y, 80, Blocks.COBBLESTONE.getDefaultState());
    }

    private static IBlockState glass(EnumDyeColor c) {
        return Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR, c);
    }

    private static void buildHall(WorldServer w, BlockPos b) {
        IBlockState brick = Blocks.STONEBRICK.getDefaultState(), glow = Blocks.GLOWSTONE.getDefaultState();
        IBlockState air = Blocks.AIR.getDefaultState();
        int x0 = 60, x1 = 84, z0 = 0, z1 = 24, top = 8;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = 0; y <= top; y++) {
                    boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                    if (y == top) set(w, b, x, y, z, ((x % 4 == 0) && (z % 4 == 0)) ? glow : brick);
                    else if (wall) set(w, b, x, y, z, ((y == 2 || y == 5) && ((x + z) % 3 == 0)) ? glow : brick);
                    else set(w, b, x, y, z, air);
                }
        // north wall: a strip of glowstone over a row of wall signs, workstations below
        for (int x = 63; x <= 77; x++) {
            set(w, b, x, 3, z1, glow);
            set(w, b, x, 2, z1, brick);
            set(w, b, x, 2, z1 - 1, Blocks.WALL_SIGN.getDefaultState().withProperty(BlockWallSign.FACING, EnumFacing.NORTH));
        }
        set(w, b, 64, 0, 23, Blocks.FURNACE.getDefaultState());
        set(w, b, 65, 0, 23, Blocks.FURNACE.getDefaultState());
        set(w, b, 66, 0, 23, Blocks.CRAFTING_TABLE.getDefaultState());
        set(w, b, 67, 0, 23, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.NORTH));
        set(w, b, 68, 0, 23, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.NORTH));
        set(w, b, 70, 0, 23, Blocks.ENCHANTING_TABLE.getDefaultState());
        set(w, b, 72, 0, 23, Blocks.ANVIL.getDefaultState());
        set(w, b, 73, 0, 23, Blocks.ENDER_CHEST.getDefaultState());
        // a trench in the floor with chests sunk into it (their lids below floor level)
        for (int x = 64; x <= 77; x++) {
            set(w, b, x, -1, 21, (x % 2 == 0) ? Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.NORTH)
                                              : Blocks.TRAPPED_CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.NORTH));
            set(w, b, x, -1, 20, air);
        }
        set(w, b, 71, -1, 19, Blocks.CHEST.getDefaultState());
        set(w, b, 70, 0, 18, Blocks.STANDING_SIGN.getDefaultState());
        // a wooden staircase against the west wall, like a real base
        for (int i = 0; i < 6; i++) set(w, b, 61, i, 6 + i, Blocks.OAK_STAIRS.getDefaultState()
                .withProperty(net.minecraft.block.BlockStairs.FACING, EnumFacing.SOUTH));
        // doorway east into a long corridor lit by glowstone in both walls
        for (int y = 0; y <= 2; y++) for (int z = 11; z <= 13; z++) set(w, b, x1, y, z, air);
        for (int x = x1 + 1; x <= 112; x++)
            for (int z = 10; z <= 14; z++)
                for (int y = 0; y <= 4; y++) {
                    boolean shell = z == 10 || z == 14 || y == 4;
                    if (!shell) { set(w, b, x, y, z, air); continue; }
                    boolean g = (z != 12 || y != 4) && y >= 1 && y <= 3 && ((x + y) % 4 == 0);
                    set(w, b, x, y, z, g ? glow : brick);
                }
        for (int z = 10; z <= 14; z++) for (int y = 0; y <= 4; y++) set(w, b, 113, y, z, brick);
    }

    private static void set(WorldServer w, BlockPos b, int x, int y, int z, IBlockState s) {
        w.setBlockState(b.add(x, y, z), s, 3);
    }
}
