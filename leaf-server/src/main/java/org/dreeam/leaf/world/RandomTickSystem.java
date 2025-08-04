package org.dreeam.leaf.world;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.list.ShortList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.material.FluidState;

public final class RandomTickSystem {
    private static final long SCALE = 0x100000L;
    private static final long TICK_FILTER_MASK = 0b1111L;
    private static final int BITS_STEP = 4;
    private static final int BITS_MAX = 60;
    private static final long CHUNK_BLOCKS = 4096L / 4L;

    private final LongArrayList queue = new LongArrayList();
    private long[] samples = LongArrays.EMPTY_ARRAY;
    private long[] weights = LongArrays.EMPTY_ARRAY;

    public void tick(ServerLevel world) {
        queue.clear();

        final BitRandomSource random = world.simpleRandom;
        final ReferenceList<LevelChunk> entityTickingChunks = world.moonrise$getEntityTickingChunks();
        final int randomTickSpeed = world.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        final LevelChunk[] raw = entityTickingChunks.getRawDataUnchecked();
        final int size = entityTickingChunks.size();
        final boolean disableIceAndSnow = world.paperConfig().environment.disableIceAndSnow;
        if (randomTickSpeed <= 0) {
            return;
        }
        if (!disableIceAndSnow) {
            iceSnow(world, size, randomTickSpeed, random, raw);
        }
        final long weightsSum = collectTickingChunks(size, random, raw, randomTickSpeed);
        if (weightsSum != 0L) {
            sampling(random, weightsSum);
        }
        final long[] q = queue.elements();
        final int minY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(world) << 4;
        for (int k = 0, len = queue.size(); k < len; ++k) {
            final long packed = q[k];
            final LevelChunk chunk = raw[(int) (packed >>> 16)];
            tickBlock(world, chunk, (int) (packed & 0xFFFF), random, minY);
        }
    }

    private void sampling(BitRandomSource random, long weightsSum) {
        final long chosen = ((weightsSum % SCALE) >= boundedNextLong(random, SCALE))
            ? (weightsSum / SCALE + 1L)
            : (weightsSum / SCALE);
        if (chosen == 0L) {
            return;
        }

        final long[] w = weights;
        final long[] s = samples;
        long accumulated = w[0];
        final long spoke = weightsSum / chosen;
        if (spoke == 0L) return;

        long current = boundedNextLong(random, spoke);
        int i = 0;
        while (current < weightsSum) {
            while (accumulated < current) {
                i++;
                accumulated += w[i];
            }
            queue.add(s[i]);
            current += spoke;
        }
    }

    private long collectTickingChunks(int size, BitRandomSource random, LevelChunk[] raw, long randomTickSpeed) {
        int bits = 0;
        long cacheRandom = random.nextLong();
        long weightsSum = 0L;

        int i = 0;
        int m = 0;
        final long scale = randomTickSpeed * SCALE / CHUNK_BLOCKS;
        for (int j = size - 3; i < j; i++) {
            if (bits != BITS_MAX) {
                bits += BITS_STEP;
            } else {
                bits = 0;
                cacheRandom = random.nextLong();
            }
            if ((cacheRandom & (TICK_FILTER_MASK << bits)) != 0L) {
                continue;
            }
            final LevelChunk chunk1 = raw[i];
            final LevelChunk chunk2 = raw[i + 1];
            final LevelChunk chunk3 = raw[i + 2];
            final LevelChunk chunk4 = raw[i + 3];
            final long l = ((long) i) << 16;
            if (chunk1.leaf$tickingBlocksDirty) {
                populateChunkTickingCount(chunk1);
            }
            if (chunk2.leaf$tickingBlocksDirty) {
                populateChunkTickingCount(chunk2);
            }
            if (chunk3.leaf$tickingBlocksDirty) {
                populateChunkTickingCount(chunk3);
            }
            if (chunk4.leaf$tickingBlocksDirty) {
                populateChunkTickingCount(chunk4);
            }
            final int[] ticking1 = chunk1.leaf$tickingCount;
            final int[] ticking2 = chunk2.leaf$tickingCount;
            final int[] ticking3 = chunk3.leaf$tickingCount;
            final int[] ticking4 = chunk4.leaf$tickingCount;
            final int s1 = ticking1.length;
            final int s2 = ticking2.length;
            final int s3 = ticking3.length;
            final int s4 = ticking4.length;
            samples = LongArrays.grow(samples, m + s1 + s2 + s3 + s4, m);
            weights = LongArrays.grow(weights, m + s1 + s2 + s3 + s4, m);
            for (int k = 0; k < s1; k++, m++) {
                int packed = ticking1[k];
                long weight = (packed >>> 16) * scale;
                weightsSum += weight;
                samples[m] = l | (packed & 0xFFFFL);
                weights[m] = weight;
            }
            for (int k = 0; k < s2; k++, m++) {
                int packed = ticking2[k];
                long weight = (packed >>> 16) * scale;
                weightsSum += weight;
                samples[m] = (l + 0x10000L) | (packed & 0xFFFFL);
                weights[m] = weight;
            }
            for (int k = 0; k < s3; k++, m++) {
                int packed = ticking3[k];
                long weight = (packed >>> 16) * scale;
                weightsSum += weight;
                samples[m] = (l + 0x20000L) | (packed & 0xFFFFL);
                weights[m] = weight;
            }
            for (int k = 0; k < s4; k++, m++) {
                int packed = ticking4[k];
                long weight = (packed >>> 16) * scale;
                weightsSum += weight;
                samples[m] = (l + 0x30000L) | (packed & 0xFFFFL);
                weights[m] = weight;
            }
        }
        for (; i < size; i++) {
            if ((random.nextInt() & ((int) (TICK_FILTER_MASK >>> 2))) != 0) {
                continue;
            }
            final LevelChunk chunk = raw[i];
            if (chunk.leaf$tickingBlocksDirty) {
                populateChunkTickingCount(chunk);
            }
            samples = LongArrays.grow(samples, m + chunk.leaf$tickingCount.length, m);
            weights = LongArrays.grow(weights, m + chunk.leaf$tickingCount.length, m);
            for (int packed : chunk.leaf$tickingCount) {
                long weight = (packed >>> 16) * scale;
                weightsSum += weight;
                samples[m] = ((long) i << 16) | (packed & 0xFFFFL);
                weights[m] = weight;
                m++;
            }
        }
        return weightsSum;
    }

    private static void populateChunkTickingCount(LevelChunk chunk) {
        chunk.leaf$tickingBlocksDirty = false;
        int sum = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            sum += (section.moonrise$getTickingBlockList().size() == 0) ? 0 : 1;
        }

        if (chunk.leaf$tickingCount.length != sum) {
            chunk.leaf$tickingCount = new int[sum];
        }

        int k = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int j = 0; j < sections.length; j++) {
            ShortList list = sections[j].moonrise$getTickingBlockList();
            int n = list.size();
            if (n != 0) {
                chunk.leaf$tickingCount[k++] = (n << 16) | (j & 0xFFFF);
            }
        }
    }

    private static void iceSnow(ServerLevel world, int size, int randomTickSpeed, BitRandomSource random, LevelChunk[] raw) {
        int currentIceAndSnowTick = random.nextInt(48 * 16);
        for (int i = 0; i < size; i++) {
            currentIceAndSnowTick -= randomTickSpeed;
            if (currentIceAndSnowTick <= 0) {
                currentIceAndSnowTick = random.nextInt(48 * 16);
                LevelChunk chunk = raw[i];
                ChunkPos pos = chunk.getPos();
                world.tickPrecipitation(world.getBlockRandomPos(pos.getMinBlockX(), 0, pos.getMinBlockZ(), 15));
            }
        }
    }

    private static void tickBlock(ServerLevel world, LevelChunk chunk, int sectionIdx, BitRandomSource random, int minSection) {
        LevelChunkSection section = chunk.getSection(sectionIdx);
        ShortList list = section.moonrise$getTickingBlockList();
        int size = list.size();
        if (size == 0) return;
        short location = list.getRaw(boundedNextInt(random, size));
        BlockState state = section.states.get(location);
        final BlockPos pos = new BlockPos((location & 15) | (chunk.locX << 4), (location >>> 8) | (minSection + (sectionIdx << 4)), ((location >>> 4) & 15) | (chunk.locZ << 4));
        state.randomTick(world, pos, random);

        final boolean doubleTickFluids = !ca.spottedleaf.moonrise.common.PlatformHooks.get().configFixMC224294();
        if (doubleTickFluids) {
            final FluidState fluidState = state.getFluidState();
            if (fluidState.isRandomlyTicking()) {
                fluidState.randomTick(world, pos, random);
            }
        }
    }

    private static long boundedNextLong(BitRandomSource rng, long bound) {
        final long m = bound - 1L;
        long r = rng.nextLong();
        if ((bound & m) == 0L) {
            r &= m;
        } else {
            //noinspection StatementWithEmptyBody
            for (long u = r >>> 1;
                 u + m - (r = u % bound) < 0L;
                 u = rng.nextLong() >>> 1)
                ;
        }
        return r;
    }

    private static int boundedNextInt(BitRandomSource rng, int bound) {
        final int m = bound - 1;
        int r = rng.nextInt();
        if ((bound & m) == 0) {
            r &= m;
        } else {
            //noinspection StatementWithEmptyBody
            for (int u = r >>> 1;
                 u + m - (r = u % bound) < 0;
                 u = rng.nextInt() >>> 1)
                ;
        }
        return r;
    }
}
