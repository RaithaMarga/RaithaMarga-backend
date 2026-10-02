package com.raithamarga.backend.service;

import com.raithamarga.backend.dto.ApiDtos.RequirementRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RequirementService {

    private final FirestoreRepository repository;
    private final UserService users;

    public RequirementService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.users = users;
    }

    public Map<String, Object> create(Actor actor, RequirementRequest request) {
        users.requireRole(actor, UserRole.BUYER);
        Map<String, Object> buyer = repository.find("buyers", actor.uid())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "profile_required",
                        "Complete your buyer profile before creating a requirement."));
        if (!"VERIFIED".equals(buyer.get("verificationStatus"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "buyer_not_verified",
                    "Only manually verified buyers can publish requirements.");
        }
        if (!DataValues.normalize(request.location()).equals(DataValues.normalize(String.valueOf(buyer.get("district"))))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "district_mismatch",
                    "Requirement location must match the district in your buyer profile.");
        }
        Map<String, Object> requirement = DataValues.map(
                "buyerId", actor.uid(),
                "crop", request.crop().trim(),
                "quantity", DataValues.number(request.quantity()),
                "unit", request.unit().trim(),
                "quality", request.quality() == null ? null : request.quality().trim(),
                "location", request.location().trim(),
                "targetPrice", DataValues.number(request.targetPrice()),
                "status", "ACTIVE",
                "createdAt", DataValues.now());
        String id = repository.create("requirements", requirement);
        requirement.put("id", id);
        return requirement;
    }

    public List<Map<String, Object>> list(Actor actor, String crop, String district) {
        Map<String, Object> user = users.me(actor);
        UserRole role = UserService.role(user);
        if (role == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "profile_required",
                    "Create a farmer or buyer profile to view requirements.");
        }
        return repository.list("requirements").stream()
                .filter(requirement -> "ACTIVE".equals(requirement.get("status")))
                .filter(requirement -> crop == null || DataValues.normalize(crop)
                        .equals(DataValues.normalize(String.valueOf(requirement.get("crop")))))
                .filter(requirement -> district == null || DataValues.normalize(district)
                        .equals(DataValues.normalize(String.valueOf(requirement.get("location")))))
                .filter(requirement -> role == UserRole.BUYER
                        ? actor.uid().equals(requirement.get("buyerId"))
                        : isVerifiedBuyer(String.valueOf(requirement.get("buyerId"))))
                .toList();
    }

    private boolean isVerifiedBuyer(String buyerId) {
        return repository.find("buyers", buyerId)
                .map(buyer -> "VERIFIED".equals(buyer.get("verificationStatus")))
                .orElse(false);
    }
}
