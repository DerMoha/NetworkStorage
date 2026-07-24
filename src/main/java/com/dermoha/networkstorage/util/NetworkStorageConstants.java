package com.dermoha.networkstorage.util;

public final class NetworkStorageConstants {

    private NetworkStorageConstants() {
    }

    public static final long SEARCH_TIMEOUT_TICKS = 600L;
    public static final long SEARCH_TIMEOUT_MS = SEARCH_TIMEOUT_TICKS * 50L;
    public static final long RESET_CONFIRMATION_WINDOW_MS = 30_000L;
    public static final long DELETE_CONFIRMATION_WINDOW_MS = 30_000L;
    public static final long BSTATS_STORED_ITEM_CACHE_TTL_MS = 30_000L;
    public static final long ITEM_CACHE_TTL_MS = 500L;
    public static final int DEFAULT_PAGE = 0;
    public static final int TICKS_PER_SECOND = 20;
    public static final int SECONDS_PER_MINUTE = 60;
    public static final int MS_PER_SECOND = 1000;
    public static final int TICKS_PER_HOUR = TICKS_PER_SECOND * SECONDS_PER_MINUTE * 60;
    public static final int DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 6;
    public static final int MAX_UPDATE_CHECK_INTERVAL_HOURS = 168;
    public static final long UPDATE_CHECK_MIN_RESCHEDULE_MS = 30_000L;
    public static final String UPDATE_USER_AGENT_PREFIX = "DerMoha/NetworkStorage/";
    public static final String UPDATE_MODRINTH_URL = "https://modrinth.com/plugin/networkstorage";
}
