package com.raithamarga.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.raithamarga.backend.dto.ApiDtos.BuyerProfileRequest;
import com.raithamarga.backend.dto.ApiDtos.FarmerProfileRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class UserService {

    private final FirestoreRepository repository;
    private final Firestore firestore;

    public UserService(FirestoreRepository repository) {
        this.repository = repository;
        this.firestore = repository.firestore();
    }

    public Map<String, Object> me(Actor actor) {
        DocumentReference userReference = firestore.collection("users").document(actor.uid());
        Map<String, Object> user;
        try {
            Map<String, Object> created = DataValues.map(
                    "uid", actor.uid(),
                    "name", actor.displayName(),
                    "email", actor.email(),
                    "status", "ACTIVE",
                    "createdAt", DataValues.now());
            user = firestore.runTransaction(transaction -> {
                DocumentSnapshot existing = transaction.get(userReference).get();
                if (existing.exists()) {
                    return new LinkedHashMap<>(existing.getData());
                }
                transaction.create(userReference, created);
                return created;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw databaseUnavailable();
        }
        if ("DISABLED".equals(user.get("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This account is disabled.");
        }
        UserRole role = role(user);
        Map<String, Object> response = new LinkedHashMap<>(user);
        if (role == UserRole.FARMER || role == UserRole.BUYER) {
            String profileCollection = role == UserRole.FARMER ? "farmers" : "buyers";
            repository.find(profileCollection, actor.uid()).ifPresent(profile -> response.put("profile", profile));
        }
        return response;
    }

    public void requireRole(Actor actor, UserRole expectedRole) {
        Map<String, Object> user = repository.find("users", actor.uid())
                .orElseThrow(() -> forbidden("Complete your " + expectedRole.name().toLowerCase()
                        + " profile before using this feature."));
        if (role(user) != expectedRole) {
            throw forbidden("This operation is only available to " + expectedRole.name().toLowerCase() + " accounts.");
        }
        if ("DISABLED".equals(user.get("status"))) {
            throw forbidden("This account is disabled.");
        }
    }

    public Map<String, Object> saveFarmerProfile(Actor actor, FarmerProfileRequest request) {
        Map<String, Object> profile = DataValues.map(
                "uid", actor.uid(),
                "village", request.village().trim(),
                "taluk", request.taluk().trim(),
                "district", request.district().trim(),
                "state", request.state().trim(),
                "landAcres", DataValues.number(request.landAcres()),
                "verificationStatus", "PENDING",
                "updatedAt", DataValues.now());
        return saveProfile(actor, UserRole.FARMER, request.fullName(), "farmers", profile);
    }

    public Map<String, Object> saveBuyerProfile(Actor actor, BuyerProfileRequest request) {
        Map<String, Object> profile = DataValues.map(
                "uid", actor.uid(),
                "businessName", request.businessName().trim(),
                "district", request.district().trim(),
                "verificationStatus", "PENDING",
                "updatedAt", DataValues.now());
        return saveProfile(actor, UserRole.BUYER, request.fullName(), "buyers", profile);
    }

    private Map<String, Object> saveProfile(
            Actor actor, UserRole role, String fullName, String profileCollection, Map<String, Object> profile) {
        DocumentReference userReference = firestore.collection("users").document(actor.uid());
        DocumentReference profileReference = firestore.collection(profileCollection).document(actor.uid());
        String now = DataValues.now();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot existing = transaction.get(userReference).get();
                Map<String, Object> user = existing.exists()
                        ? new LinkedHashMap<>(existing.getData()) : new LinkedHashMap<>();
                UserRole existingRole = role(user);
                if (existingRole != null && existingRole != role) {
                    throw conflict("This account is already registered as " + existingRole.name().toLowerCase() + ".");
                }
                if ("DISABLED".equals(user.get("status"))) {
                    throw forbidden("This account is disabled.");
                }

                DocumentSnapshot existingProfile = transaction.get(profileReference).get();
                if (existingProfile.exists()) {
                    Object verificationStatus = existingProfile.get("verificationStatus");
                    if (verificationStatus != null) {
                        profile.put("verificationStatus", verificationStatus);
                    }
                    Object createdAt = existingProfile.get("createdAt");
                    if (createdAt != null) {
                        profile.put("createdAt", createdAt);
                    }
                } else {
                    profile.put("createdAt", now);
                }
                user.put("uid", actor.uid());
                user.put("name", fullName.trim());
                user.put("email", actor.email());
                user.put("role", role.name());
                user.put("status", "ACTIVE");
                user.putIfAbsent("createdAt", now);
                user.put("updatedAt", now);
                transaction.set(userReference, user);
                transaction.set(profileReference, profile);
                return profile;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw databaseUnavailable();
        }
    }

    public static UserRole role(Map<String, Object> user) {
        Object value = user.get("role");
        if (value == null) {
            return null;
        }
        try {
            return UserRole.valueOf(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_account_role",
                    "The account has an invalid role. Contact support.");
        }
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    private ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "role_conflict", message);
    }

    private ApiException databaseUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "The data service is temporarily unavailable.");
    }
}
