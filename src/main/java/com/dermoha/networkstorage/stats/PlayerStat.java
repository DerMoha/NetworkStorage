package com.dermoha.networkstorage.stats;

import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

public class PlayerStat {

    private static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String UNKNOWN_PLAYER_NAME = "Unknown Player";

    private final UUID playerUUID;
    private final String playerName;
    private final LongAdder itemsDeposited = new LongAdder();
    private final LongAdder itemsWithdrawn = new LongAdder();
    public PlayerStat(UUID playerUUID, String playerName) {
        this(playerUUID, playerName, 0, 0);
    }

    public PlayerStat(UUID playerUUID, String playerName, long itemsDeposited, long itemsWithdrawn) {
        this.playerUUID = playerUUID;
        this.playerName = validatePlayerName(playerName);
        this.itemsDeposited.add(itemsDeposited);
        this.itemsWithdrawn.add(itemsWithdrawn);
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

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getItemsDeposited() {
        return itemsDeposited.sum();
    }

    public long getItemsWithdrawn() {
        return itemsWithdrawn.sum();
    }

    public void addItemsDeposited(long amount) {
        if (amount > 0) {
            itemsDeposited.add(amount);
        }
    }

    public void addItemsWithdrawn(long amount) {
        if (amount > 0) {
            itemsWithdrawn.add(amount);
        }
    }
}
