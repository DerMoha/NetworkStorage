package com.dermoha.networkstorage.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;

import java.util.function.Predicate;

public final class BlockUtils {

    private BlockUtils() {
    }

    /**
     * Pure logic: returns the BlockFace direction toward the other half of a double chest.
     * Returns null if the chest is SINGLE or facing is not a horizontal cardinal direction.
     *
     * <p>Per the Chest.Type Javadoc, LEFT and RIGHT are relative to the chest itself (opposite
     * to the player's perspective when placing). A LEFT chest's other half is at 90 degrees
     * clockwise from the chest's facing; a RIGHT chest's other half is at 90 degrees
     * counter-clockwise.
     */
    public static BlockFace findOtherChestHalf(Chest.Type type, BlockFace facing) {
        if (type == Chest.Type.SINGLE) {
            return null;
        }
        boolean isLeft = type == Chest.Type.LEFT;
        return switch (facing) {
            case NORTH -> isLeft ? BlockFace.EAST : BlockFace.WEST;
            case SOUTH -> isLeft ? BlockFace.WEST : BlockFace.EAST;
            case EAST -> isLeft ? BlockFace.SOUTH : BlockFace.NORTH;
            case WEST -> isLeft ? BlockFace.NORTH : BlockFace.SOUTH;
            default -> null;
        };
    }

    /**
     * Bukkit wrapper: returns the Location of the other half of a double chest, or null if the
     * block is not a network container, not a chest-shaped block, or is SINGLE.
     *
     * <p>Reads {@link org.bukkit.block.data.type.Chest} block data, which is shared by vanilla
     * chests and copper chest variants in Paper 1.21.4+ (no separate CopperChest block data
     * class). The {@code isNetworkContainer} predicate gates the Material via
     * {@code ConfigManager.isNetworkContainerBlock}.
     */
    public static Location getOtherDoubleChestHalf(Block block, Predicate<Material> isNetworkContainer) {
        if (block == null || isNetworkContainer == null) {
            return null;
        }
        if (!isNetworkContainer.test(block.getType())) {
            return null;
        }
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return null;
        }
        BlockFace direction = findOtherChestHalf(chestData.getType(), chestData.getFacing());
        return direction == null ? null : block.getRelative(direction).getLocation();
    }
}
