package com.raithamarga.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class DataValues {

    private DataValues() {
    }

    static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] != null) {
                result.put((String) entries[i], entries[i + 1]);
            }
        }
        return result;
    }

    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String string) {
            return new BigDecimal(string);
        }
        return BigDecimal.ZERO;
    }

    static double number(BigDecimal value) {
        return value.doubleValue();
    }

    static String now() {
        return Instant.now().toString();
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
