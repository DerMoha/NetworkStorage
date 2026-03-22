package com.dermoha.networkstorage.stats;

import java.util.UUID;
import java.util.regex.Pattern;

public class PlayerStat {

    private static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String UNKNOWN_PLAYER_NAME = "Unknown Player";

    private final UUID playerUUID;
    private final String playerName;
    private long itemsDeposited;
    private long itemsWithdrawn;

    public PlayerStat(UUID playerUUID, String playerName) {
        this(playerUUID, playerName, 0, 0);
    }

    public PlayerStat(UUID playerUUID, String playerName, long itemsDeposited, long itemsWithdrawn) {
        this.playerUUID = playerUUID;
        this.playerName = validatePlayerName(playerName);
        this.itemsDeposited = itemsDeposited;
        this.itemsWithdrawn = itemsWithdrawn;
    }

    private static String validatePlayerName(String playerName) {
        if (playerName == null) {
            return UNKNOWN_PLAYER_NAME;
        }

        String trimmedName = playerName.trim();
        if (VALID_PLAYER_NAME.matcher(trimmedName).matches()) {
            return trimmedName;
        }

        return UNKNOWN_PLAYER_NAME;
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
