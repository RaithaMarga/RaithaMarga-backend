package com.raithamarga.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.raithamarga.backend.dto.ApiDtos.AdminUserStatusRequest;
import com.raithamarga.backend.dto.ApiDtos.AdminVerificationUpdateRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.DealStatus;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class AdminService {

    private static final List<String> VERIFICATION_STATUSES = List.of("PENDING", "VERIFIED", "REJECTED");
    private static final List<String> USER_STATUSES = List.of("ACTIVE", "DISABLED");

    private final FirestoreRepository repository;
    private final Firestore firestore;
    private final UserService users;

    public AdminService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.firestore = repository.firestore();
        this.users = users;
    }

    public Map<String, Object> overview(Actor actor) {
        requireAdmin(actor);
        List<Map<String, Object>> farmers = repository.list("farmers");
        List<Map<String, Object>> buyers = repository.list("buyers");
        List<Map<String, Object>> listings = repository.list("listings");
        List<Map<String, Object>> requirements = repository.list("requirements");
        List<Map<String, Object>> deals = repository.list("deals");
        List<Map<String, Object>> users = repository.list("users");
        List<Map<String, Object>> enrichedDeals = adminDealDetails(deals, farmers, buyers, listings, users);
        Map<String, Long> dealsByStatus = new LinkedHashMap<>();
        for (DealStatus status : DealStatus.values()) {
            dealsByStatus.put(status.name(), deals.stream()
                    .filter(deal -> status.name().equals(deal.get("status")))
                    .count());
        }
        return DataValues.map(
                "totalFarmers", farmers.size(),
                "totalBuyers", buyers.size(),
                "pendingFarmers", countVerification(farmers, "PENDING"),
                "pendingBuyers", countVerification(buyers, "PENDING"),
                "verifiedBuyers", countVerification(buyers, "VERIFIED"),
                "activeListings", listings.stream().filter(item -> "ACTIVE".equals(item.get("status"))).count(),
                "activeRequirements", requirements.stream().filter(item -> "ACTIVE".equals(item.get("status"))).count(),
                "dealsByStatus", dealsByStatus,
                "managedInterestCount", enrichedDeals.stream()
                        .filter(deal -> Boolean.TRUE.equals(deal.get("managed")))
                        .filter(deal -> "INTERESTED".equals(deal.get("status")))
                        .count(),
                "newInterestRequests", enrichedDeals.stream()
                        .filter(deal -> Boolean.TRUE.equals(deal.get("managed")))
                        .filter(deal -> "INTERESTED".equals(deal.get("status")))
                        .sorted(newestFirst())
                        .limit(5)
                        .toList());
    }

    public List<Map<String, Object>> verifications(Actor actor, String roleFilter, String statusFilter) {
        requireAdmin(actor);
        UserRole role = parseRole(roleFilter, true);
        if (role == UserRole.ADMIN) {
            throw badRequest("role", "Verification role must be FARMER or BUYER.");
        }
        String status = statusFilter == null || statusFilter.isBlank() ? "PENDING" : statusFilter;
        requireValue(status, VERIFICATION_STATUSES, "verification status");

        List<Map<String, Object>> result = new ArrayList<>();
        if (role == null || role == UserRole.FARMER) {
            appendVerifications(result, "farmers", UserRole.FARMER, status);
        }
        if (role == null || role == UserRole.BUYER) {
            appendVerifications(result, "buyers", UserRole.BUYER, status);
        }
        result.sort(newestFirst());
        return result;
    }

    public Map<String, Object> updateVerification(
            Actor actor, String roleValue, String uid, AdminVerificationUpdateRequest request) {
        requireAdmin(actor);
        UserRole role = parseRole(roleValue, false);
        if (role != UserRole.FARMER && role != UserRole.BUYER) {
            throw badRequest("role", "Verification role must be FARMER or BUYER.");
        }
        requireValue(request.status(), VERIFICATION_STATUSES, "verification status");

        String profileCollection = role == UserRole.FARMER ? "farmers" : "buyers";
        DocumentReference profileReference = firestore.collection(profileCollection).document(uid);
        DocumentReference userReference = firestore.collection("users").document(uid);
        DocumentReference auditReference = firestore.collection("auditLogs").document();
        String now = DataValues.now();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot profileSnapshot = transaction.get(profileReference).get();
                if (!profileSnapshot.exists()) {
                    throw notFound("profile_not_found", "The verification profile was not found.");
                }
                DocumentSnapshot userSnapshot = transaction.get(userReference).get();
                Map<String, Object> profile = new LinkedHashMap<>(profileSnapshot.getData());
                Map<String, Object> before = verificationAuditValues(profile);
                profile.put("verificationStatus", request.status());
                profile.put("verifiedBy", actor.uid());
                if ("VERIFIED".equals(request.status())) {
                    profile.put("verifiedAt", now);
                } else {
                    profile.remove("verifiedAt");
                }
                if (note == null) {
                    profile.remove("verificationNote");
                } else {
                    profile.put("verificationNote", note);
                }
                profile.put("updatedAt", now);
                Map<String, Object> after = verificationAuditValues(profile);
                transaction.set(profileReference, profile);
                transaction.create(auditReference, auditEntry(
                        actor.uid(), "VERIFICATION_UPDATED", uid, role.name(), before, after, note, now));
                return verificationRecord(uid, role, profile,
                        userSnapshot.exists() ? userSnapshot.getData() : Map.of());
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

    public List<Map<String, Object>> users(Actor actor, String roleFilter, String statusFilter) {
        requireAdmin(actor);
        UserRole role = parseRole(roleFilter, true);
        if (statusFilter != null && !statusFilter.isBlank()) {
            requireValue(statusFilter, USER_STATUSES, "user status");
        }
        List<Map<String, Object>> result = new ArrayList<>(repository.list("users").stream()
                .filter(user -> role == null || role.name().equals(user.get("role")))
                .filter(user -> statusFilter == null || statusFilter.isBlank()
                        || statusFilter.equals(user.get("status")))
                .map(this::safeUser)
                .toList());
        // Farmers added by the team have no login (no users document), so list them from the farmers collection.
        boolean wantsActive = statusFilter == null || statusFilter.isBlank() || "ACTIVE".equals(statusFilter);
        if ((role == null || role == UserRole.FARMER) && wantsActive) {
            repository.list("farmers").stream()
                    .filter(farmer -> Boolean.TRUE.equals(farmer.get("managed")))
                    .filter(farmer -> !"REMOVED".equals(farmer.get("status")))
                    .map(farmer -> DataValues.map(
                            "uid", farmer.get("id"),
                            "role", UserRole.FARMER.name(),
                            "name", farmer.get("name"),
                            "phone", farmer.get("phone"),
                            "managed", true,
                            "status", "ACTIVE",
                            "createdAt", farmer.get("createdAt"),
                            "updatedAt", farmer.get("updatedAt")))
                    .forEach(result::add);
        }
        result.sort(newestFirst());
        return result;
    }

    public Map<String, Object> updateUserStatus(
            Actor actor, String uid, AdminUserStatusRequest request) {
        requireAdmin(actor);
        requireValue(request.status(), USER_STATUSES, "user status");
        DocumentReference userReference = firestore.collection("users").document(uid);
        DocumentReference auditReference = firestore.collection("auditLogs").document();
        String now = DataValues.now();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot userSnapshot = transaction.get(userReference).get();
                if (!userSnapshot.exists()) {
                    throw notFound("user_not_found", "The user was not found.");
                }
                Map<String, Object> user = new LinkedHashMap<>(userSnapshot.getData());
                UserRole role = UserService.role(user);
                if ("DISABLED".equals(request.status()) && actor.uid().equals(uid)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                            "You cannot disable your own admin account.");
                }
                if ("DISABLED".equals(request.status()) && role == UserRole.ADMIN) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                            "An admin account cannot be disabled through this endpoint.");
                }
                Map<String, Object> before = userStatusAuditValues(user);
                user.put("status", request.status());
                user.put("updatedAt", now);
                Map<String, Object> after = userStatusAuditValues(user);
                transaction.set(userReference, user);
                transaction.create(auditReference, auditEntry(
                        actor.uid(), "USER_STATUS_UPDATED", uid,
                        role == null ? null : role.name(), before, after, null, now));
                return safeUser(user);
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

    public List<Map<String, Object>> listings(Actor actor) {
        requireAdmin(actor);
        return newestCapped(repository.list("listings"), 200);
    }

    public List<Map<String, Object>> deals(Actor actor) {
        requireAdmin(actor);
        List<Map<String, Object>> deals = repository.list("deals");
        List<Map<String, Object>> farmers = repository.list("farmers");
        List<Map<String, Object>> buyers = repository.list("buyers");
        List<Map<String, Object>> listings = repository.list("listings");
        List<Map<String, Object>> users = repository.list("users");
        return newestCapped(adminDealDetails(deals, farmers, buyers, listings, users), 200);
    }

    public List<Map<String, Object>> auditLogs(Actor actor) {
        requireAdmin(actor);
        List<Map<String, Object>> users = repository.list("users");
        List<Map<String, Object>> farmers = repository.list("farmers");
        Map<String, Map<String, Object>> usersById = indexById(users);
        Map<String, Map<String, Object>> farmersById = indexById(farmers);
        List<Map<String, Object>> entries = repository.list("auditLogs").stream().map(entry -> {
            Map<String, Object> enriched = new LinkedHashMap<>(entry);
            String adminUid = String.valueOf(entry.getOrDefault("adminUid", ""));
            Map<String, Object> adminUser = usersById.getOrDefault(adminUid, Map.of());
            String targetUid = String.valueOf(entry.getOrDefault("targetUid", ""));
            Map<String, Object> targetFarmer = farmersById.getOrDefault(targetUid, Map.of());
            Object adminName = entry.get("adminName");
            if (adminName == null) {
                adminName = adminUser.get("name");
            }
            Object targetName = entry.get("targetName");
            if (targetName == null) {
                targetName = targetFarmer.get("name");
            }
            if (adminName != null) {
                enriched.put("adminName", adminName);
            }
            if (adminUser.get("email") != null) {
                enriched.put("adminEmail", adminUser.get("email"));
            }
            if (targetName != null) {
                enriched.put("targetName", targetName);
            }
            return enriched;
        }).toList();
        return newestCapped(entries, 100);
    }

    private List<Map<String, Object>> adminDealDetails(
            List<Map<String, Object>> deals, List<Map<String, Object>> farmers,
            List<Map<String, Object>> buyers, List<Map<String, Object>> listings,
            List<Map<String, Object>> users) {
        Map<String, Map<String, Object>> farmersById = indexById(farmers);
        Map<String, Map<String, Object>> buyersById = indexById(buyers);
        Map<String, Map<String, Object>> listingsById = indexById(listings);
        Map<String, Map<String, Object>> usersById = indexById(users);
        return deals.stream().map(deal -> {
            Map<String, Object> listing = listingsById.getOrDefault(String.valueOf(deal.get("listingId")), Map.of());
            Map<String, Object> farmer = farmersById.getOrDefault(String.valueOf(deal.get("farmerId")), Map.of());
            Map<String, Object> buyer = buyersById.getOrDefault(String.valueOf(deal.get("buyerId")), Map.of());
            Map<String, Object> buyerUser = usersById.getOrDefault(String.valueOf(deal.get("buyerId")), Map.of());
            boolean managed = Boolean.TRUE.equals(farmer.get("managed"));
            Map<String, Object> result = new LinkedHashMap<>(deal);
            result.put("managed", managed);
            putIfPresent(result, "farmerName", firstValue(farmer.get("name"), listing.get("farmerName"),
                    usersById.getOrDefault(String.valueOf(deal.get("farmerId")), Map.of()).get("name")));
            if (managed) {
                putIfPresent(result, "farmerPhone", farmer.get("phone"));
            } else {
                result.remove("farmerPhone");
            }
            putIfPresent(result, "buyerName", firstValue(buyerUser.get("name"), buyer.get("fullName"), buyer.get("name")));
            putIfPresent(result, "buyerContact", firstValue(buyer.get("phone"), buyer.get("contactPhone"),
                    buyerUser.get("phone"), buyerUser.get("email"), buyer.get("email")));
            putIfPresent(result, "crop", listing.get("crop"));
            putIfPresent(result, "unit", listing.get("unit"));
            return result;
        }).toList();
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> records) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            Object id = record.getOrDefault("id", record.get("uid"));
            if (id != null) {
                indexed.put(String.valueOf(id), record);
            }
        }
        return indexed;
    }

    private Object firstValue(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> destination, String key, Object value) {
        if (value != null) {
            destination.put(key, value);
        }
    }

    private void requireAdmin(Actor actor) {
        users.requireRole(actor, UserRole.ADMIN);
    }

    private void appendVerifications(
            List<Map<String, Object>> result, String collection, UserRole role, String status) {
        for (Map<String, Object> profile : repository.list(collection)) {
            if (!status.equals(profile.getOrDefault("verificationStatus", "PENDING"))) {
                continue;
            }
            String uid = String.valueOf(profile.getOrDefault("uid", profile.get("id")));
            Map<String, Object> user = repository.find("users", uid).orElseGet(Map::of);
            result.add(verificationRecord(uid, role, profile, user));
        }
    }

    private Map<String, Object> verificationRecord(
            String uid, UserRole role, Map<String, Object> profile, Map<String, Object> user) {
        return DataValues.map(
                "uid", uid,
                "role", role.name(),
                "name", user.get("name"),
                "email", user.get("email"),
                "village", profile.get("village"),
                "taluk", profile.get("taluk"),
                "district", profile.get("district"),
                "landAcres", profile.get("landAcres"),
                "businessName", profile.get("businessName"),
                "verificationStatus", profile.getOrDefault("verificationStatus", "PENDING"),
                "createdAt", profile.get("createdAt"),
                "verifiedAt", profile.get("verifiedAt"),
                "verificationNote", profile.get("verificationNote"));
    }

    private Map<String, Object> safeUser(Map<String, Object> user) {
        return DataValues.map(
                "uid", user.getOrDefault("uid", user.get("id")),
                "role", user.get("role"),
                "name", user.get("name"),
                "email", user.get("email"),
                "status", user.get("status"),
                "createdAt", user.get("createdAt"),
                "updatedAt", user.get("updatedAt"));
    }

    private Map<String, Object> auditEntry(
            String adminUid, String action, String targetUid, String targetRole,
            Map<String, Object> before, Map<String, Object> after, String note, String at) {
        return DataValues.map(
                "adminUid", adminUid,
                "action", action,
                "targetUid", targetUid,
                "targetRole", targetRole,
                "before", before,
                "after", after,
                "note", note,
                "at", at);
    }

    private Map<String, Object> verificationAuditValues(Map<String, Object> profile) {
        return DataValues.map(
                "verificationStatus", profile.get("verificationStatus"),
                "verificationNote", profile.get("verificationNote"),
                "verifiedAt", profile.get("verifiedAt"),
                "verifiedBy", profile.get("verifiedBy"));
    }

    private Map<String, Object> userStatusAuditValues(Map<String, Object> user) {
        return DataValues.map("status", user.get("status"));
    }

    private long countVerification(List<Map<String, Object>> profiles, String status) {
        return profiles.stream()
                .filter(profile -> status.equals(profile.getOrDefault("verificationStatus", "PENDING")))
                .count();
    }

    private List<Map<String, Object>> newestCapped(List<Map<String, Object>> records, int limit) {
        return records.stream().sorted(newestFirst()).limit(limit).toList();
    }

    private Comparator<Map<String, Object>> newestFirst() {
        return Comparator.comparing(
                record -> String.valueOf(record.getOrDefault("createdAt", record.get("at"))),
                Comparator.reverseOrder());
    }

    private UserRole parseRole(String value, boolean allowNull) {
        if (value == null || value.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw badRequest("role", "Role is required.");
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest("role", "Role must be FARMER, BUYER, or ADMIN.");
        }
    }

    private void requireValue(String value, List<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw badRequest("invalid_" + label.replace(' ', '_'),
                    "The " + label + " is invalid.");
        }
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException databaseUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "The data service is temporarily unavailable.");
    }
}