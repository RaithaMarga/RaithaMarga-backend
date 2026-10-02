package com.raithamarga.backend.service;

import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.DealStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DealStatusMachineTest {

    @Test
    void allowsOnlyTheExpectedDealLifecycle() {
        assertDoesNotThrow(() -> DealStatusMachine.requireTransition(DealStatus.INTERESTED, DealStatus.CONFIRMED));
        assertDoesNotThrow(() -> DealStatusMachine.requireTransition(DealStatus.CONFIRMED, DealStatus.IN_PROGRESS));
        assertDoesNotThrow(() -> DealStatusMachine.requireTransition(DealStatus.IN_PROGRESS, DealStatus.COMPLETED));
        assertDoesNotThrow(() -> DealStatusMachine.requireTransition(DealStatus.INTERESTED, DealStatus.CANCELLED));
        assertDoesNotThrow(() -> DealStatusMachine.requireTransition(DealStatus.CONFIRMED, DealStatus.FAILED));
    }

    @Test
    void rejectsSkippingLifecycleStepsAndReopeningTerminalDeals() {
        assertThrows(ApiException.class,
                () -> DealStatusMachine.requireTransition(DealStatus.INTERESTED, DealStatus.COMPLETED));
        assertThrows(ApiException.class,
                () -> DealStatusMachine.requireTransition(DealStatus.COMPLETED, DealStatus.CANCELLED));
        assertThrows(ApiException.class,
                () -> DealStatusMachine.requireTransition(DealStatus.CANCELLED, DealStatus.CONFIRMED));
    }
}
