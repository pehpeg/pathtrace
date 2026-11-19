package com.pathtrace.client;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the frame time goes: GPU time per pass (timestamp queries, read back a few frames later so
 * nothing stalls) and CPU time per step, averaged. Shown by /pathtrace perf.
 */
final class PerfStats {
    private static final int RING = 4;          // frames in flight
    private static final int MAX_MARKS = 16;

    private boolean gpu, inited;
    private final int[][] queries = new int[RING][MAX_MARKS];
    private final String[][] names = new String[RING][MAX_MARKS];
    private final int[] used = new int[RING];
    private int slot;
    private long frames;

    private final Map<String, double[]> gpuAvg = new LinkedHashMap<>();   // name -> {avg ms}
    private final Map<String, double[]> cpuAvg = new LinkedHashMap<>();
    private long cpuStart;
    private String cpuName;

    private void init() {
        inited = true;
        boolean ok;
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            ok = caps.OpenGL33 || caps.GL_ARB_timer_query;
        } catch (Throwable t) {
            ok = false;
        }
        gpu = ok;
        if (gpu) {
            for (int r = 0; r < RING; r++)
                for (int i = 0; i < MAX_MARKS; i++) queries[r][i] = GL15.glGenQueries();
        }
    }

    boolean hasGpuTimers() {
        if (!inited) return false;
        return gpu;
    }

    /** Start of a frame's GPU work: collect the results of the frame that used this slot before. */
    void beginFrame() {
        if (!inited) init();
        frames++;
        slot = (slot + 1) % RING;
        if (gpu && used[slot] > 1) {
            int n = used[slot];
            if (GL15.glGetQueryObjecti(queries[slot][n - 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long prev = GL33.glGetQueryObjectui64(queries[slot][0], GL15.GL_QUERY_RESULT);
                for (int i = 1; i < n; i++) {
                    long t = GL33.glGetQueryObjectui64(queries[slot][i], GL15.GL_QUERY_RESULT);
                    add(gpuAvg, names[slot][i], (t - prev) / 1.0e6);
                    prev = t;
                }
            }
        }
        used[slot] = 0;
        mark("start");
    }

    /** Everything on the GPU since the previous mark is billed to `name`. */
    void mark(String name) {
        if (!gpu || used[slot] >= MAX_MARKS) return;
        int i = used[slot]++;
        names[slot][i] = name;
        GL33.glQueryCounter(queries[slot][i], GL33.GL_TIMESTAMP);
    }

    /** Bill a measured duration to a CPU step directly. */
    void cpuAdd(String name, double ms) {
        add(cpuAvg, name, ms);
    }

    void cpuBegin(String name) {
        cpuName = name;
        cpuStart = System.nanoTime();
    }

    void cpuEnd() {
        if (cpuName == null) return;
        add(cpuAvg, cpuName, (System.nanoTime() - cpuStart) / 1.0e6);
        cpuName = null;
    }

    // per entry: {average (exponential, ~20 frames), worst since reset, frames over 4 ms since reset}
    private static void add(Map<String, double[]> m, String k, double ms) {
        double[] a = m.computeIfAbsent(k, x -> new double[]{ms, 0, 0});
        a[0] += (ms - a[0]) * 0.05;
        a[1] = Math.max(a[1], ms);
        if (ms > 4.0) a[2]++;
    }

    String report() {
        StringBuilder sb = new StringBuilder();
        double total = 0;
        if (!gpu) sb.append("GPU timers not supported by this driver\n");
        else {
            sb.append("GPU (ms):");
            for (Map.Entry<String, double[]> e : gpuAvg.entrySet()) {
                sb.append(' ').append(e.getKey()).append(' ').append(String.format("%.2f", e.getValue()[0]));
                total += e.getValue()[0];
            }
            sb.append(String.format(" | total %.2f\n", total));
        }
        double ct = 0;
        sb.append("CPU (ms):");
        for (Map.Entry<String, double[]> e : cpuAvg.entrySet()) {
            sb.append(' ').append(e.getKey()).append(' ').append(String.format("%.2f", e.getValue()[0]));
            ct += e.getValue()[0];
        }
        sb.append(String.format(" | total %.2f\n", ct));
        sb.append("CPU worst frame (ms) / frames over 4 ms:");
        for (Map.Entry<String, double[]> e : cpuAvg.entrySet())
            sb.append(' ').append(e.getKey()).append(' ').append(String.format("%.1f/%d", e.getValue()[1], (int) e.getValue()[2]));
        return sb.toString();
    }

    void reset() {
        gpuAvg.clear();
        cpuAvg.clear();
    }
}
