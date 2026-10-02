package com.raithamarga.backend.controller;

import com.raithamarga.backend.dto.ApiDtos.DealCreateRequest;
import com.raithamarga.backend.dto.ApiDtos.DealStatusRequest;
import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.DealService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deals")
public class DealController {

    private final DealService deals;

    public DealController(DealService deals) {
        this.deals = deals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @Valid @RequestBody DealCreateRequest request) {
        return deals.create(actor, request);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return deals.list(actor);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String id) {
        return deals.get(actor, id);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> updateStatus(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String id,
            @Valid @RequestBody DealStatusRequest request) {
        return deals.updateStatus(actor, id, request.status());
    }
}
