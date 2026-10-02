package com.raithamarga.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MatchingServiceTest {

    private final Map<String, Object> listing = Map.of(
            "id", "listing-1",
            "crop", " Ragi ",
            "quantity", 100D,
            "price", 2200D,
            "grade", "A",
            "unit", "kg");
    private final Map<String, Object> requirement = Map.of(
            "id", "requirement-1",
            "crop", "ragi",
            "quantity", 50D,
            "targetPrice", 2000D,
            "quality", "B",
            "unit", "kg",
            "location", "Mysuru");

    @Test
    void matchesCaseInsensitiveCropWhenAllMvpCriteriaAreMet() {
        Map<String, Object> match = MatchingService.evaluate(
                listing, requirement, new BigDecimal("100"), " mysuru ");

        assertNotNull(match);
        assertEquals(74, match.get("score"));
        assertEquals(6, ((java.util.List<?>) match.get("reasons")).size());
    }

    @Test
    void rejectsWrongCropDistrictPriceQualityOrInsufficientQuantity() {
        assertNull(MatchingService.evaluate(listing, requirement, new BigDecimal("49"), "Mysuru"));
        assertNull(MatchingService.evaluate(listing, requirement, new BigDecimal("100"), "Mandya"));
        assertNull(MatchingService.evaluate(listing, Map.of(
                "id", "requirement-3",
                "crop", "ragi",
                "quantity", 50D,
                "targetPrice", 1900D,
                "quality", "B",
                "unit", "kg",
                "location", "Mysuru"), new BigDecimal("100"), "Mysuru"));
        assertNull(MatchingService.evaluate(listing, Map.of(
                "id", "requirement-2",
                "crop", "ragi",
                "quantity", 50D,
                "targetPrice", 2000D,
                "quality", "organic",
                "unit", "kg",
                "location", "Mysuru"), new BigDecimal("100"), "Mysuru"));
        assertNull(MatchingService.evaluate(listing, Map.of(
                "id", "requirement-4",
                "crop", "ragi",
                "quantity", 50D,
                "targetPrice", 2000D,
                "quality", "B",
                "unit", "quintal",
                "location", "Mysuru"), new BigDecimal("100"), "Mysuru"));
    }
}
