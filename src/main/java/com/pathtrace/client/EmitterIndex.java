package com.pathtrace.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Every light-emitting block in the loaded chunks, per 16x16x16 chunk section, independent of
 * the voxel volume (which only covers 128 blocks). Sections are (re)scanned when chunks load or
 * blocks change, a budgeted number per frame.
 * Packed entry: x | y << 4 | z << 8 | light << 12 | colour << 16 | offset << 19  (x/y/z inside the section)
 * <p>
 * offset (0..26, 13 = none) says which way the light is pushed out of its block: full glowing blocks
 * and liquids (glowstone, sea lanterns, lava...) have their light placed just outside their open
 * faces, so a light set flush into a floor or wall still lights the surfaces around it.
 * <p>
 * The same scan records which blocks of each section are solid (4096 bits, index (y*16+z)*16+x);
 * {@link LodVolume} builds the far shadow grid from those.
 */
final class EmitterIndex {
    private static final int[] EMPTY = new int[0];

    private final HashMap<Long, int[]> sections = new HashMap<>();
    private final HashMap<Long, long[]> occupancy = new HashMap<>();   // absent = no solid cells
    private final ArrayDeque<Long> changedOccupancy = new ArrayDeque<>();
    private final long[] solidBits = new long[64];
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int[] scratch = new int[256];
    private World world;
    /** Increments whenever the set of emitters changes. */
    int version;

    static long key(int sx, int sy, int sz) {
        return new BlockPos(sx, sy, sz).toLong();
    }

    boolean isFor(World w) {
        return w == world;
    }

    void reset(World w) {
        world = w;
        sections.clear();
        occupancy.clear();
        changedOccupancy.clear();
        queue.clear();
        queued.clear();
        version++;
    }

    private void enqueue(int sx, int sy, int sz, boolean urgent) {
        if (sy < 0 || sy > 15) return;
        long k = key(sx, sy, sz);
        if (!queued.add(k)) return;
        if (urgent) queue.addFirst(k);
        else queue.addLast(k);
    }

    void markBlock(BlockPos p) {
        enqueue(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4, true);
    }

    void markRange(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int sx = x1 >> 4; sx <= x2 >> 4; sx++)
            for (int sz = z1 >> 4; sz <= z2 >> 4; sz++)
                for (int sy = Math.max(0, y1 >> 4); sy <= Math.min(15, y2 >> 4); sy++)
                    enqueue(sx, sy, sz, false);
    }

    /** Make sure every section in this block range has been scanned at least once. */
    void ensureScanned(int x1, int z1, int x2, int z2) {
        for (int sx = x1 >> 4; sx <= x2 >> 4; sx++)
            for (int sz = z1 >> 4; sz <= z2 >> 4; sz++)
                for (int sy = 0; sy <= 15; sy++)
                    if (!sections.containsKey(key(sx, sy, sz))) enqueue(sx, sy, sz, false);
    }

    /** Drop sections far outside the area of interest (unloaded chunks, places we left). */
    void prune(int cx, int cz, int radius) {
        java.util.function.Predicate<Long> far = k -> {
            BlockPos s = BlockPos.fromLong(k);
            return Math.abs((s.getX() << 4) + 8 - cx) > radius || Math.abs((s.getZ() << 4) + 8 - cz) > radius;
        };
        sections.keySet().removeIf(far);
        occupancy.keySet().removeIf(far);
    }

    void process(World w, int budget) {
        if (w != world) reset(w);
        while (budget-- > 0 && !queue.isEmpty()) {
            long k = queue.pollFirst();
            queued.remove(k);
            BlockPos s = BlockPos.fromLong(k);
            scan(s.getX(), s.getY(), s.getZ(), k);
        }
    }

    private void scan(int sx, int sy, int sz, long k) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(sx, sz);
        if (chunk == null) {
            if (sections.remove(k) != null) version++;
            if (occupancy.remove(k) != null) changedOccupancy.add(k);
            return;
        }
        ExtendedBlockStorage es = chunk.getBlockStorageArray()[sy];
        int n = 0;
        java.util.Arrays.fill(solidBits, 0L);
        boolean anySolid = false;
        if (es != null && !es.isEmpty()) {
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        IBlockState st = es.get(x, y, z);
                        Material m = st.getMaterial();
                        if (m == Material.AIR) continue;
                        if (st.isOpaqueCube() || m == Material.LEAVES) {
                            int bit = (y * 16 + z) * 16 + x;
                            solidBits[bit >> 6] |= 1L << (bit & 63);
                            anySolid = true;
                        }
                        pos.setPos((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                        int light = st.getLightValue(world, pos);
                        if (light <= 0) continue;
                        if (n == scratch.length) scratch = java.util.Arrays.copyOf(scratch, n * 2);
                        int offset = 13;
                        if (st.isFullCube() || m.isLiquid()) offset = openFaces(pos, m);
                        scratch[n++] = x | y << 4 | z << 8 | Math.min(15, light) << 12
                                | LightColors.forBlock(st.getBlock()) << 16 | offset << 19;
                    }
        }
        long[] mask = anySolid ? solidBits.clone() : null;
        long[] oldMask = mask == null ? occupancy.remove(k) : occupancy.put(k, mask);
        if (!java.util.Arrays.equals(oldMask, mask)) changedOccupancy.add(k);

        int[] entries = n == 0 ? EMPTY : java.util.Arrays.copyOf(scratch, n);
        int[] old = sections.put(k, entries);
        if (old == null ? entries.length > 0 : !java.util.Arrays.equals(old, entries)) version++;
    }

    private final BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();

    /** Direction code (dx+1) + 3(dy+1) + 9(dz+1) of the average open face of a block, 13 if none/all. */
    private int openFaces(BlockPos p, Material self) {
        int sx = 0, sy = 0, sz = 0;
        for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.values()) {
            net.minecraft.util.math.Vec3i d = f.getDirectionVec();
            npos.setPos(p.getX() + d.getX(), p.getY() + d.getY(), p.getZ() + d.getZ());
            IBlockState n = world.getBlockState(npos);
            Material nm = n.getMaterial();
            boolean open = nm == Material.AIR || (!n.isOpaqueCube() && !nm.isLiquid() && nm != self);
            if (open) {
                sx += d.getX();
                sy += d.getY();
                sz += d.getZ();
            }
        }
        if (sx == 0 && sy == 0 && sz == 0) return 13;
        return (Integer.signum(sx) + 1) + 3 * (Integer.signum(sy) + 1) + 9 * (Integer.signum(sz) + 1);
    }

    /** 4096-bit solid mask of a section, or null if it has nothing solid. Bit index (y*16+z)*16+x. */
    long[] occupancy(int sx, int sy, int sz) {
        return occupancy.get(key(sx, sy, sz));
    }

    /** Sections whose occupancy changed since the last call (consumed). */
    Long pollChangedOccupancy() {
        return changedOccupancy.pollFirst();
    }

    interface Visitor {
        void accept(int x, int y, int z, int light, int color, int offset);
    }

    /** Visit all known emitters inside the block range (inclusive). */
    void forEach(int x1, int y1, int z1, int x2, int y2, int z2, Visitor v) {
        for (Map.Entry<Long, int[]> e : sections.entrySet()) {
            int[] arr = e.getValue();
            if (arr.length == 0) continue;
            BlockPos s = BlockPos.fromLong(e.getKey());
            int bx = s.getX() << 4, by = s.getY() << 4, bz = s.getZ() << 4;
            if (bx + 15 < x1 || bx > x2 || by + 15 < y1 || by > y2 || bz + 15 < z1 || bz > z2) continue;
            for (int p : arr) {
                int x = bx + (p & 15), y = by + ((p >> 4) & 15), z = bz + ((p >> 8) & 15);
                if (x < x1 || x > x2 || y < y1 || y > y2 || z < z1 || z > z2) continue;
                v.accept(x, y, z, (p >> 12) & 15, (p >> 16) & 7, (p >>> 19) & 31);
            }
        }
    }
}
