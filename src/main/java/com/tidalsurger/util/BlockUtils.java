package com.tidalsurger.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;

import java.util.Set;

/**
 * Block helpers shared by the surf trail and the water cube.
 */
public final class BlockUtils {

    private BlockUtils() {
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * True when the block may safely be swapped for temporary water: it has no
     * collision, is not on the skip list and holds no tile-entity data (signs,
     * chests, banners, ...) that a BlockData snapshot cannot carry.
     * Solid terrain, containers and everything unbreakable are never touched.
     */
    public static boolean isReplaceable(Block block, Set<Material> skip) {
        Material type = block.getType();
        if (skip.contains(type)) {
            return false;
        }
        if (!block.isPassable()) {
            return false;
        }
        if (type.isAir()) {
            return true;
        }
        BlockState state = block.getState(false);
        return !(state instanceof TileState);
    }
}
