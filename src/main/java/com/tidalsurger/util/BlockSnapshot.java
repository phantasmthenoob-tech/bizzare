package com.tidalsurger.util;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Immutable record of a block that the plugin replaced, so the original state
 * can be restored exactly (type, rotation, waterlogging, ...).
 */
public final class BlockSnapshot {

    private final Block block;
    private final BlockData data;
    private final int placedTick;

    public BlockSnapshot(Block block, BlockData data, int placedTick) {
        this.block = block;
        this.data = data;
        this.placedTick = placedTick;
    }

    public Block block() {
        return block;
    }

    public BlockData data() {
        return data;
    }

    public int placedTick() {
        return placedTick;
    }

    /**
     * Restores the original block data without triggering neighbour physics.
     *
     * @param onlyIfWater when true the block is only restored if it still holds
     *                    the water this plugin placed (never clobbers edits made
     *                    by other plugins or players meanwhile).
     */
    public void restore(boolean onlyIfWater) {
        if (onlyIfWater && block.getType() != org.bukkit.Material.WATER) {
            return;
        }
        block.setBlockData(data, false);
    }
}
