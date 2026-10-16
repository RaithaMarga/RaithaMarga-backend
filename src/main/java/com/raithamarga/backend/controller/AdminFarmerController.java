package com.raithamarga.backend.controller;

import com.raithamarga.backend.dto.ApiDtos.AdminFarmerRequest;
import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.ManagedFarmerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin-only: farmers added by the team from WhatsApp (name + phone, no email / login). */
@RestController
@RequestMapping("/api/admin/farmers")
public class AdminFarmerController {

    private final ManagedFarmerService farmers;

    public AdminFarmerController(ManagedFarmerService farmers) {
        this.farmers = farmers;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @Valid @RequestBody AdminFarmerRequest request) {
        return farmers.create(actor, request);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return farmers.list(actor);
    }

    // POST (not DELETE) because the CORS configuration only allows GET, POST and PATCH.
    @PostMapping("/{id}/remove")
    public Map<String, Object> remove(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String id) {
        return farmers.remove(actor, id);
    }
}
