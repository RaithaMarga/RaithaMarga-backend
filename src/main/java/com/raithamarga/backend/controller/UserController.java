package com.raithamarga.backend.controller;

import com.raithamarga.backend.dto.ApiDtos.BuyerProfileRequest;
import com.raithamarga.backend.dto.ApiDtos.ContactRequest;
import com.raithamarga.backend.dto.ApiDtos.FarmerProfileRequest;
import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.ContactService;
import com.raithamarga.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService users;
    private final ContactService contacts;

    public UserController(UserService users, ContactService contacts) {
        this.users = users;
        this.contacts = contacts;
    }

    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return users.me(actor);
    }

    @PostMapping("/farmers/profile")
    public Map<String, Object> saveFarmerProfile(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @Valid @RequestBody FarmerProfileRequest request) {
        return users.saveFarmerProfile(actor, request);
    }

    @PostMapping("/buyers/profile")
    public Map<String, Object> saveBuyerProfile(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @Valid @RequestBody BuyerProfileRequest request) {
        return users.saveBuyerProfile(actor, request);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, String>> contact(
            @Valid @RequestBody ContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contacts.submit(request));
    }
}
