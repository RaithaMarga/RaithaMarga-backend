package com.raithamarga.backend.service;

import com.raithamarga.backend.dto.ApiDtos.ListingRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ListingService {

    private final FirestoreRepository repository;
    private final UserService users;

    public ListingService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.users = users;
    }

    public Map<String, Object> create(Actor actor, ListingRequest request) {
        users.requireRole(actor, UserRole.FARMER);
        Map<String, Object> profile = repository.find("farmers", actor.uid())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "profile_required",
                        "Complete your farmer profile before creating a listing."));
        Map<String, Object> listing = DataValues.map(
                "farmerId", actor.uid(),
                "farmerName", repository.find("users", actor.uid())
                        .map(user -> user.get("name"))
                        .orElse(null),
                "crop", request.crop().trim(),
                "quantity", DataValues.number(request.quantity()),
                "reservedQuantity", 0D,
                "soldQuantity", 0D,
                "unit", request.unit().trim(),
                "grade", request.grade() == null ? null : request.grade().trim(),
                "price", DataValues.number(request.price()),
                "district", request.district().trim(),
                "taluk", request.taluk(),
                "village", request.village(),
                "state", request.state(),
                "availabilityDate", request.availabilityDate().toString(),
                "status", "ACTIVE",
                "createdAt", DataValues.now());
        if (!DataValues.normalize(request.district())
                .equals(DataValues.normalize(String.valueOf(profile.get("district"))))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "district_mismatch",
                    "Listing district must match the district in your farmer profile.");
        }
        String id = repository.create("listings", listing);
        listing.put("id", id);
        listing.put("availableQuantity", DataValues.number(request.quantity()));
        return listing;
    }

    public List<Map<String, Object>> list(Actor actor, String crop, String district) {
        users.me(actor);
        return repository.list("listings").stream()
                .filter(listing -> "ACTIVE".equals(listing.get("status")))
                .filter(listing -> DataValues.decimal(listing.get("quantity"))
                        .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                        .subtract(DataValues.decimal(listing.get("soldQuantity"))).signum() > 0)
                .filter(listing -> crop == null || DataValues.normalize(crop)
                        .equals(DataValues.normalize(String.valueOf(listing.get("crop")))))
                .filter(listing -> district == null || DataValues.normalize(district)
                        .equals(DataValues.normalize(String.valueOf(listing.get("district")))))
                .map(this::withAvailability)
                .toList();
    }

    /** Aggregated availability per crop and unit: no farmer names, phone numbers or locations. */
    public List<Map<String, Object>> publicAvailability() {
        Map<String, Map<String, Object>> byCropUnit = new java.util.LinkedHashMap<>();
        for (Map<String, Object> listing : repository.list("listings")) {
            if (!"ACTIVE".equals(listing.get("status"))) {
                continue;
            }
            java.math.BigDecimal available = DataValues.decimal(withAvailability(listing).get("availableQuantity"));
            if (available.signum() <= 0) {
                continue;
            }
            String crop = String.valueOf(listing.get("crop")).trim();
            String unit = String.valueOf(listing.get("unit")).trim();
            String key = DataValues.normalize(crop) + "|" + DataValues.normalize(unit);
            java.math.BigDecimal price = DataValues.decimal(listing.get("price"));
            Map<String, Object> entry = byCropUnit.computeIfAbsent(key, ignored -> DataValues.map(
                    "crop", crop,
                    "unit", unit,
                    "availableQuantity", 0D,
                    "minPrice", DataValues.number(price),
                    "listingCount", 0));
            entry.put("availableQuantity", DataValues.number(
                    DataValues.decimal(entry.get("availableQuantity")).add(available)));
            if (price.signum() > 0 && price.compareTo(DataValues.decimal(entry.get("minPrice"))) < 0) {
                entry.put("minPrice", DataValues.number(price));
            }
            entry.put("listingCount", ((Integer) entry.get("listingCount")) + 1);
        }
        return List.copyOf(byCropUnit.values());
    }

    public Map<String, Object> get(Actor actor, String id) {
        users.me(actor);
        return repository.find("listings", id)
                .filter(listing -> "ACTIVE".equals(listing.get("status")))
                .map(this::withAvailability)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "listing_not_found",
                        "Listing was not found or is no longer available."));
    }

    private Map<String, Object> withAvailability(Map<String, Object> listing) {
        listing.remove("farmerPhone");
        listing.remove("phone");
        if (Boolean.TRUE.equals(listing.get("managed"))
                || String.valueOf(listing.get("farmerId")).startsWith(ManagedFarmerService.ID_PREFIX)) {
            listing.remove("farmerId");
        }
        listing.put("availableQuantity", DataValues.number(
                DataValues.decimal(listing.get("quantity"))
                        .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                        .subtract(DataValues.decimal(listing.get("soldQuantity"))).max(java.math.BigDecimal.ZERO)));
        return listing;
    }
}
