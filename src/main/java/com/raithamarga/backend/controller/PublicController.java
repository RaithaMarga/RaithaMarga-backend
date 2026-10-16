package com.raithamarga.backend.controller;

import com.raithamarga.backend.service.ListingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public, read-only endpoints (no sign-in). They never expose farmer or buyer details. */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final ListingService listings;

    public PublicController(ListingService listings) {
        this.listings = listings;
    }

    /** Total available quantity and lowest price per crop, for the public home page. */
    @GetMapping("/availability")
    public List<Map<String, Object>> availability() {
        return listings.publicAvailability();
    }
}
