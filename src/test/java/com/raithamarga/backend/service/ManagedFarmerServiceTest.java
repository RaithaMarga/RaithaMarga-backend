package com.raithamarga.backend.service;

import com.raithamarga.backend.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedFarmerServiceTest {

    @Test
    void phoneKeyKeepsLastTenDigits() {
        assertEquals("9876543210", ManagedFarmerService.phoneKey("9876543210"));
        assertEquals("9876543210", ManagedFarmerService.phoneKey("+91 98765-43210"));
        assertEquals("9876543210", ManagedFarmerService.phoneKey("09876543210"));
    }

    @Test
    void phoneKeyRejectsShortNumbers() {
        assertThrows(ApiException.class, () -> ManagedFarmerService.phoneKey("12345"));
        assertThrows(ApiException.class, () -> ManagedFarmerService.phoneKey(null));
    }
}
