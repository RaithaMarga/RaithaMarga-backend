package com.raithamarga.backend.controller;

import com.raithamarga.backend.dto.ApiDtos.BidActionRequest;
import com.raithamarga.backend.dto.ApiDtos.BidCreateRequest;
import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.BidService;
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
@RequestMapping("/api/bids")
public class BidController {

    private final BidService bids;

    public BidController(BidService bids) {
        this.bids = bids;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @Valid @RequestBody BidCreateRequest request) {
        return bids.create(actor, request);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return bids.list(actor);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> act(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String id,
            @Valid @RequestBody BidActionRequest request) {
        return bids.act(actor, id, request);
    }
}
