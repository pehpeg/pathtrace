package com.pathtrace.client;

import com.pathtrace.PTConfig;
import com.pathtrace.PathTraceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * CI test in a normally generated world (-Dpathtrace.autoshot.mode=terrain): flies the player across
 * the terrain at a steady speed, logging how long each of our CPU steps takes per frame (averages,
 * worst frames, how often a step spikes) and taking a few screenshots along the way. Stutter in
 * real worlds comes from chunks loading while moving, which the flat test world barely exercises.
 */
final class TerrainRun {
    static boolean requested() {
        return "terrain".equals(System.getProperty("pathtrace.autoshot.mode"));
    }

    private static final long SETTLE_MS = 30000, FLY_MS = 60000;
    private static final double SPEED = 10.0;   // blocks per second, about sprint-flying

    private boolean started;
    private int phase;          // 0 waiting for world, 1 settling, 2 flying, 3 done
    private long phaseStart;
    private int frames, shots;
    private long lastFrame;
    private double x0, z0, flyY;
    private final double[] frameMs = new double[100000];
    private int frameCount;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || started) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiMainMenu) {
            started = true;
            mc.gameSettings.renderDistanceChunks = 8;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.tutorialStep = TutorialSteps.NONE;
            mc.getTutorial().setStep(TutorialSteps.NONE);
            WorldSettings ws = new WorldSettings(424242L, GameType.CREATIVE, true, false, WorldType.DEFAULT);
            ws.enableCommands();
            PathTraceMod.LOG.info("[terrain] creating world");
            mc.launchIntegratedServer("pathtrace_terrain", "pathtrace_terrain", ws);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !started) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || mc.getIntegratedServer() == null) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        long now = System.currentTimeMillis();
        frames++;
        if (phase == 0) {
            phase = 1;
            phaseStart = now;
            IntegratedServer server = mc.getIntegratedServer();
            server.addScheduledTask(() -> {
                WorldServer w = server.getWorld(0);
                w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                w.setWorldTime(6000L);
                EntityPlayerMP p = server.getPlayerList().getPlayers().get(0);
                p.capabilities.isFlying = true;
                p.sendPlayerAbilities();
            });
            x0 = mc.player.posX;
            z0 = mc.player.posZ;
            flyY = mc.world.getHeight((int) x0, (int) z0) + 12;
            return;
        }
        if (phase == 1) {
            // chunks arrive while settling: start above the real ground once it's there
            int ground = mc.world.getHeight((int) Math.floor(x0), (int) Math.floor(z0));
            if (ground > 0) flyY = Math.max(ground, 63) + 12;
            place(mc, 0.0);
            if (now - phaseStart > SETTLE_MS) {
                phase = 2;
                phaseStart = now;
                lastFrame = System.nanoTime();
                ClientHandler.perfReset();
                PathTraceMod.LOG.info("[terrain] flying");
            }
            return;
        }
        if (phase == 2) {
            long t = System.nanoTime();
            if (frameCount < frameMs.length) frameMs[frameCount++] = (t - lastFrame) / 1.0e6;
            lastFrame = t;
            double secs = (now - phaseStart) / 1000.0;
            place(mc, secs * SPEED);
            if (secs > shots * 12.0 + 6.0) {
                ScreenShotHelper.saveScreenshot(mc.gameDir, "t" + shots + "_terrain_pathtrace.png",
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                shots++;
            }
            if (now - phaseStart > FLY_MS) {
                phase = 3;
                report();
                ClientHandler.setEnabled(false);
                phaseStart = now;
            }
            return;
        }
        if (phase == 3 && now - phaseStart > 3000) {
            ScreenShotHelper.saveScreenshot(mc.gameDir, "t9_terrain_vanilla.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            PathTraceMod.LOG.info("[terrain] done");
            mc.shutdown();
            phase = 4;
        }
    }

    /** Fly east along the terrain, looking ahead and a bit down. */
    private void place(Minecraft mc, double dist) {
        double x = x0 + dist, z = z0;
        int ground = mc.world.getHeight((int) Math.floor(x + 24), (int) Math.floor(z));
        if (ground > 0) flyY += ((Math.max(ground, 63) + 10) - flyY) * 0.02;   // follow the terrain smoothly
        mc.player.setPositionAndRotation(x, flyY, z, -90f, 20f);
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }

    private void report() {
        double[] f = java.util.Arrays.copyOf(frameMs, frameCount);
        java.util.Arrays.sort(f);
        double sum = 0;
        for (double v : f) sum += v;
        int over50 = 0, over100 = 0;
        for (double v : f) { if (v > 50) over50++; if (v > 100) over100++; }
        PathTraceMod.LOG.info(String.format("[terrain] frames %d, avg %.1f ms, median %.1f, p99 %.1f, max %.1f, >50ms %d, >100ms %d",
                f.length, sum / Math.max(1, f.length), f.length > 0 ? f[f.length / 2] : 0, f.length > 0 ? f[(int) (f.length * 0.99)] : 0,
                f.length > 0 ? f[f.length - 1] : 0, over50, over100));
        PathTraceMod.LOG.info("[terrain] " + ClientHandler.perfReport().replace('\n', ' '));
        PathTraceMod.LOG.info("[terrain] settings: renderScale " + PTConfig.renderScale + ", directScale " + PTConfig.directScale);
    }
}
