package com.dermoha.networkstorage.storage;

import org.bukkit.Location;
import org.bukkit.World;

/** A persisted block position that does not require its Bukkit world to be loaded. */
public record StoredLocation(String world, int x, int y, int z) {

    public static StoredLocation from(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("A stored location must have a world");
        }
        return new StoredLocation(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location resolve(World loadedWorld) {
        if (loadedWorld == null || !loadedWorld.getName().equals(world)) {
            throw new IllegalArgumentException("Loaded world does not match stored world " + world);
        }
        return new Location(loadedWorld, x, y, z);
    }
}
