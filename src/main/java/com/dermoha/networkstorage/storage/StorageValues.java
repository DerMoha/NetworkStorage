package com.dermoha.networkstorage.storage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Shared, format-level validation for values crossing the storage seam. */
public final class StorageValues {

    private static final Pattern NETWORK_NAME = Pattern.compile("^[A-Za-z0-9 _'-]{1,32}$");
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private StorageValues() {
    }

    public static boolean isValidNetworkName(String value) {
        return value != null && NETWORK_NAME.matcher(value).matches();
    }

    public static boolean isValidPlayerName(String value) {
        return value != null && PLAYER_NAME.matcher(value.trim()).matches();
    }

    public static long exactLong(Object rawValue, String field) {
        if (!(rawValue instanceof Number number)) {
            throw new StorageException(field + " is not numeric");
        }
        try {
            if (number instanceof BigDecimal decimal) {
                return decimal.longValueExact();
            }
            if (number instanceof BigInteger integer) {
                return integer.longValueExact();
            }
        } catch (ArithmeticException e) {
            throw new StorageException(field + " is outside the supported integer range", e);
        }
        long value = number.longValue();
        if (number instanceof Float || number instanceof Double) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal) || decimal != value) {
                throw new StorageException(field + " is not an exact integer");
            }
        }
        return value;
    }
}
