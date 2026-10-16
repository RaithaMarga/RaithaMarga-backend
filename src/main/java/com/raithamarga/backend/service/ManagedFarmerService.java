package com.raithamarga.backend.service;

import com.raithamarga.backend.dto.ApiDtos.AdminFarmerRequest;
import com.raithamarga.backend.dto.ApiDtos.AdminProduceRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Farmers added by the RaithaMarga team. They are stored in the same {@code farmers} collection as
 * self-registered farmers, but have no Firebase account and no email: the document id is derived from
 * the 10-digit phone number, which also makes the phone number unique.
 */
@Service
public class ManagedFarmerService {

    public static final String ID_PREFIX = "managed-";
    private static final Set<String> OPEN_DEAL_STATUSES = Set.of("INTERESTED", "CONFIRMED", "IN_PROGRESS");

    private final FirestoreRepository repository;
    private final UserService users;

    public ManagedFarmerService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.users = users;
    }

    /** Creates the farmer (or updates the one with the same phone) and optionally lists their first produce. */
    public Map<String, Object> create(Actor actor, AdminFarmerRequest request) {
        users.requireRole(actor, UserRole.ADMIN);
        String phoneKey = phoneKey(request.phone());
        String id = ID_PREFIX + phoneKey;
        String now = DataValues.now();

        Optional<Map<String, Object>> existing = repository.find("farmers", id);
        boolean created = existing.isEmpty();
        String createdBy = existing.map(farmer -> farmer.get("createdBy"))
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(actor.uid());
        String createdByName = existing.map(farmer -> farmer.get("createdByName"))
                .filter(name -> !String.valueOf(name).isBlank())
                .map(String::valueOf)
                .orElseGet(() -> repository.find("users", createdBy)
                        .map(user -> user.get("name"))
                        .filter(name -> !String.valueOf(name).isBlank())
                        .map(String::valueOf)
                        .orElseGet(() -> createdBy.equals(actor.uid()) ? adminName(actor) : "team"));
        Map<String, Object> farmer = DataValues.map(
                "uid", id,
                "name", request.fullName().trim(),
                "phone", request.phone().trim(),
                "phoneKey", phoneKey,
                "village", blankToNull(request.village()),
                "taluk", blankToNull(request.taluk()),
                "district", request.district().trim(),
                "state", request.state() == null || request.state().isBlank() ? "Karnataka" : request.state().trim(),
                "notes", blankToNull(request.notes()),
                "managed", true,
                "source", "WHATSAPP",
                "status", "ACTIVE",
                // The team verified this farmer personally, so they are not queued for verification.
                "verificationStatus", "VERIFIED",
                "createdBy", createdBy,
                "createdByName", createdByName,
                "createdAt", existing.map(f -> f.get("createdAt")).orElse(now),
                "updatedAt", now);
        repository.set("farmers", id, farmer);
        audit(actor, created ? "MANAGED_FARMER_CREATED" : "MANAGED_FARMER_UPDATED",
                id, String.valueOf(farmer.get("name")), request.notes(), now);
        farmer.put("id", id);

        Map<String, Object> listing = null;
        if (request.produce() != null) {
            listing = createListing(actor, farmer, request.produce(), now);
        }
        return DataValues.map("farmer", farmer, "listing", listing, "created", created);
    }

    public List<Map<String, Object>> list(Actor actor) {
        users.requireRole(actor, UserRole.ADMIN);
        Map<String, Long> listingCounts = new HashMap<>();
        for (Map<String, Object> listing : repository.list("listings")) {
            if (Boolean.TRUE.equals(listing.get("managed")) && "ACTIVE".equals(listing.get("status"))) {
                listingCounts.merge(String.valueOf(listing.get("farmerId")), 1L, Long::sum);
            }
        }
        return repository.list("farmers").stream()
                .filter(farmer -> Boolean.TRUE.equals(farmer.get("managed")))
                .filter(farmer -> !"REMOVED".equals(farmer.get("status")))
                .peek(farmer -> {
                    farmer.put("listingCount", listingCounts.getOrDefault(String.valueOf(farmer.get("id")), 0L));
                    if (farmer.get("createdByName") == null) {
                        farmer.put("createdByName", repository.find("users", String.valueOf(farmer.get("createdBy")))
                                .map(user -> user.get("name"))
                                .orElse("team"));
                    }
                })
                .sorted(Comparator.comparing(
                        (Map<String, Object> farmer) -> String.valueOf(farmer.getOrDefault("createdAt", "")),
                        Comparator.reverseOrder()))
                .toList();
    }

    /** Soft-removes a team-added farmer and takes their active listings off the marketplace. */
    public Map<String, Object> remove(Actor actor, String id) {
        users.requireRole(actor, UserRole.ADMIN);
        Map<String, Object> farmer = repository.find("farmers", id)
                .filter(found -> Boolean.TRUE.equals(found.get("managed")))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "farmer_not_found",
                        "This team-added farmer was not found."));
        boolean hasOpenDeal = repository.list("deals").stream()
                .anyMatch(deal -> id.equals(deal.get("farmerId"))
                        && OPEN_DEAL_STATUSES.contains(String.valueOf(deal.get("status"))));
        if (hasOpenDeal) {
            throw new ApiException(HttpStatus.CONFLICT, "farmer_has_open_deals",
                    "This farmer has open deals. Complete or cancel them before removing the farmer.");
        }
        String now = DataValues.now();
        for (Map<String, Object> listing : repository.list("listings")) {
            if (id.equals(listing.get("farmerId")) && "ACTIVE".equals(listing.get("status"))) {
                String listingId = String.valueOf(listing.remove("id"));
                listing.put("status", "REMOVED");
                listing.put("updatedAt", now);
                repository.set("listings", listingId, listing);
            }
        }
        farmer.put("status", "REMOVED");
        farmer.put("updatedAt", now);
        repository.set("farmers", id, farmer);
        audit(actor, "MANAGED_FARMER_REMOVED", id, String.valueOf(farmer.get("name")),
                "Farmer removed from the marketplace.", now);
        return DataValues.map("id", id, "status", "REMOVED");
    }

    private Map<String, Object> createListing(
            Actor actor, Map<String, Object> farmer, AdminProduceRequest produce, String now) {
        String cleanCrop = produce.crop().replaceAll("\\s*\\([^)]*\\)", "").trim();
        Map<String, Object> listing = DataValues.map(
                "farmerId", farmer.get("id"),
                "farmerName", farmer.get("name"),
                "managed", true,
                "createdBy", actor.uid(),
                "crop", cleanCrop.isEmpty() ? produce.crop().trim() : cleanCrop,
                "quantity", DataValues.number(produce.quantity()),
                "reservedQuantity", 0D,
                "soldQuantity", 0D,
                "unit", produce.unit().trim(),
                "grade", blankToNull(produce.grade()),
                "price", DataValues.number(produce.price()),
                "district", farmer.get("district"),
                "taluk", farmer.get("taluk"),
                "village", farmer.get("village"),
                "state", farmer.get("state"),
                "availabilityDate", (produce.availabilityDate() == null ? LocalDate.now() : produce.availabilityDate()).toString(),
                "status", "ACTIVE",
                "createdAt", now);
        String listingId = repository.create("listings", listing);
        listing.put("id", listingId);
        listing.put("availableQuantity", DataValues.number(produce.quantity()));
        audit(actor, "MANAGED_LISTING_CREATED", listingId, String.valueOf(farmer.get("name")),
                produce.crop().trim() + " · " + produce.quantity().stripTrailingZeros().toPlainString()
                        + " " + produce.unit().trim() + " · ₹" + produce.price().stripTrailingZeros().toPlainString()
                        + " per " + produce.unit().trim(), now, farmer.get("id"), listing);
        return listing;
    }

    private void audit(Actor actor, String action, String targetId, String targetName, String note, String now) {
        audit(actor, action, targetId, targetName, note, now, targetId, null);
    }

    private void audit(
            Actor actor, String action, String targetId, String targetName, String note, String now,
            Object targetUid, Map<String, Object> produce) {
        repository.create("auditLogs", DataValues.map(
                "adminUid", actor.uid(),
                "adminName", adminName(actor),
                "action", action,
                "targetId", targetId,
                "targetUid", targetUid,
                "targetName", targetName,
                "targetRole", UserRole.FARMER.name(),
                "crop", produce == null ? null : produce.get("crop"),
                "quantity", produce == null ? null : produce.get("quantity"),
                "unit", produce == null ? null : produce.get("unit"),
                "price", produce == null ? null : produce.get("price"),
                "note", note == null || note.isBlank() ? targetName : note.trim(),
                "at", now));
    }

    private String adminName(Actor actor) {
        return repository.find("users", actor.uid())
                .map(user -> user.get("name"))
                .filter(name -> !String.valueOf(name).isBlank())
                .map(String::valueOf)
                .orElseGet(() -> actor.displayName() == null || actor.displayName().isBlank()
                        ? "team" : actor.displayName());
    }

    /** Last 10 digits of the phone number (drops +91 / 0 prefixes, spaces and dashes). */
    static String phoneKey(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_phone",
                    "Enter a valid 10-digit mobile number.");
        }
        return digits.substring(digits.length() - 10);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
