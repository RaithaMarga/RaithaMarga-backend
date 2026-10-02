package com.raithamarga.backend.controller;

import com.raithamarga.backend.dto.ApiDtos.AdminUserStatusRequest;
import com.raithamarga.backend.dto.ApiDtos.AdminVerificationUpdateRequest;
import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return admin.overview(actor);
    }

    @GetMapping("/verifications")
    public List<Map<String, Object>> verifications(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        return admin.verifications(actor, role, status);
    }

    @PatchMapping("/verifications/{role}/{uid}")
    public Map<String, Object> updateVerification(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String role,
            @PathVariable String uid,
            @Valid @RequestBody AdminVerificationUpdateRequest request) {
        return admin.updateVerification(actor, role, uid, request);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        return admin.users(actor, role, status);
    }

    @PatchMapping("/users/{uid}/status")
    public Map<String, Object> updateUserStatus(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor,
            @PathVariable String uid,
            @Valid @RequestBody AdminUserStatusRequest request) {
        return admin.updateUserStatus(actor, uid, request);
    }

    @GetMapping("/listings")
    public List<Map<String, Object>> listings(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return admin.listings(actor);
    }

    @GetMapping("/deals")
    public List<Map<String, Object>> deals(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return admin.deals(actor);
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return admin.auditLogs(actor);
    }
}
