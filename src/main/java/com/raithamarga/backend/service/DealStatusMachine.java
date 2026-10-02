package com.raithamarga.backend.service;

import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.DealStatus;
import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class DealStatusMachine {

    private static final Map<DealStatus, Set<DealStatus>> TRANSITIONS = Map.of(
            DealStatus.INTERESTED, EnumSet.of(DealStatus.CONFIRMED, DealStatus.CANCELLED, DealStatus.FAILED),
            DealStatus.CONFIRMED, EnumSet.of(DealStatus.IN_PROGRESS, DealStatus.CANCELLED, DealStatus.FAILED),
            DealStatus.IN_PROGRESS, EnumSet.of(DealStatus.COMPLETED, DealStatus.CANCELLED, DealStatus.FAILED),
            DealStatus.COMPLETED, EnumSet.noneOf(DealStatus.class),
            DealStatus.CANCELLED, EnumSet.noneOf(DealStatus.class),
            DealStatus.FAILED, EnumSet.noneOf(DealStatus.class));

    private DealStatusMachine() {
    }

    public static boolean canTransition(DealStatus current, DealStatus next) {
        return TRANSITIONS.get(current).contains(next);
    }

    public static void requireTransition(DealStatus current, DealStatus next) {
        if (!canTransition(current, next)) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_deal_transition",
                    "Deal cannot move from " + current + " to " + next + ".");
        }
    }
}
