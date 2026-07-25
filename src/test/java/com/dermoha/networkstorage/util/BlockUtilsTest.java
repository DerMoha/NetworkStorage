package com.dermoha.networkstorage.util;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockUtilsTest {

    @Test
    void singleReturnsNull() {
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.SINGLE, BlockFace.NORTH));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.SINGLE, BlockFace.SOUTH));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.SINGLE, BlockFace.EAST));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.SINGLE, BlockFace.WEST));
    }

    @Test
    void nonHorizontalFacingReturnsNull() {
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.UP));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.DOWN));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.UP));
        assertNull(BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.DOWN));
    }

    @Test
    void leftFacingNorthReturnsEast() {
        assertEquals(BlockFace.EAST, BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.NORTH));
    }

    @Test
    void rightFacingNorthReturnsWest() {
        assertEquals(BlockFace.WEST, BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.NORTH));
    }

    @Test
    void leftFacingSouthReturnsWest() {
        assertEquals(BlockFace.WEST, BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.SOUTH));
    }

    @Test
    void rightFacingSouthReturnsEast() {
        assertEquals(BlockFace.EAST, BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.SOUTH));
    }

    @Test
    void leftFacingEastReturnsSouth() {
        assertEquals(BlockFace.SOUTH, BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.EAST));
    }

    @Test
    void rightFacingEastReturnsNorth() {
        assertEquals(BlockFace.NORTH, BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.EAST));
    }

    @Test
    void leftFacingWestReturnsNorth() {
        assertEquals(BlockFace.NORTH, BlockUtils.findOtherChestHalf(Chest.Type.LEFT, BlockFace.WEST));
    }

    @Test
    void rightFacingWestReturnsSouth() {
        assertEquals(BlockFace.SOUTH, BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, BlockFace.WEST));
    }

    @Test
    void leftAndRightReturnOppositeDirections() {
        for (BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockFace leftDir = BlockUtils.findOtherChestHalf(Chest.Type.LEFT, facing);
            BlockFace rightDir = BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, facing);
            assertNotEquals(leftDir, rightDir, "LEFT and RIGHT must return opposite directions for facing " + facing);
        }
    }

    @Test
    void directionsAreAlwaysHorizontal() {
        for (BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockFace leftDir = BlockUtils.findOtherChestHalf(Chest.Type.LEFT, facing);
            BlockFace rightDir = BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, facing);
            assertNotEquals(null, leftDir);
            assertNotEquals(null, rightDir);
            assertNotEquals(BlockFace.UP, leftDir);
            assertNotEquals(BlockFace.DOWN, leftDir);
            assertNotEquals(BlockFace.UP, rightDir);
            assertNotEquals(BlockFace.DOWN, rightDir);
        }
    }
}
