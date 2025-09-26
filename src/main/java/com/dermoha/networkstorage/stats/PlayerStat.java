package com.dermoha.networkstorage.stats;

import java.util.UUID;

public class PlayerStat {

    private final UUID playerUUID;
    private final String playerName;
    private long itemsDeposited;
    private long itemsWithdrawn;

    public PlayerStat(UUID playerUUID, String playerName) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.itemsDeposited = 0;
        this.itemsWithdrawn = 0;
    }

    // Getters
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getItemsDeposited() {
        return itemsDeposited;
    }

    public long getItemsWithdrawn() {
        return itemsWithdrawn;
    }

    // Modifiers
    public void addItemsDeposited(long amount) {
        this.itemsDeposited += amount;
    }

    public void addItemsWithdrawn(long amount) {
        this.itemsWithdrawn += amount;
    }
}
