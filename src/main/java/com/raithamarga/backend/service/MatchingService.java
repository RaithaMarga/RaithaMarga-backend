package com.raithamarga.backend.service;

import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MatchingService {

    private static final BigDecimal PRICE_TOLERANCE = new BigDecimal("1.10");

    private final FirestoreRepository repository;

    public MatchingService(FirestoreRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> listFor(Actor actor) {
        UserRole role = role(actor);
        List<Map<String, Object>> results = refresh().stream()
                .filter(match -> role == UserRole.FARMER
                        ? actor.uid().equals(match.get("farmerId"))
                        : actor.uid().equals(match.get("buyerId")))
                .sorted(Comparator.comparingInt(match -> -((Number) match.get("score")).intValue()))
                .map(this::safeMatch)
                .toList();
        return results;
    }

    private Map<String, Object> safeMatch(Map<String, Object> match) {
        Map<String, Object> safe = new java.util.LinkedHashMap<>(match);
        if (String.valueOf(safe.get("farmerId")).startsWith(ManagedFarmerService.ID_PREFIX)) {
            safe.remove("farmerId");
        }
        return safe;
    }

    public List<Map<String, Object>> refresh() {
        List<Map<String, Object>> matches = new ArrayList<>();
        List<Map<String, Object>> listings = repository.list("listings");
        List<Map<String, Object>> requirements = repository.list("requirements");
        for (Map<String, Object> listing : listings) {
            if (!"ACTIVE".equals(listing.get("status"))) {
                continue;
            }
            BigDecimal available = DataValues.decimal(listing.get("quantity"))
                    .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                    .subtract(DataValues.decimal(listing.get("soldQuantity")));
            if (available.signum() <= 0) {
                continue;
            }
            String farmerId = String.valueOf(listing.get("farmerId"));
            Map<String, Object> farmer = repository.find("farmers", farmerId).orElse(null);
            if (farmer == null) {
                continue;
            }
            String listingDistrict = String.valueOf(listing.getOrDefault("district", farmer.get("district")));
            for (Map<String, Object> requirement : requirements) {
                if (!"ACTIVE".equals(requirement.get("status"))) {
                    continue;
                }
                String buyerId = String.valueOf(requirement.get("buyerId"));
                Map<String, Object> buyer = repository.find("buyers", buyerId).orElse(null);
                if (buyer == null || !"VERIFIED".equals(buyer.get("verificationStatus"))) {
                    continue;
                }
                Map<String, Object> match = evaluate(listing, requirement, available, listingDistrict);
                if (match != null) {
                    match.put("farmerId", farmerId);
                    match.put("buyerId", buyerId);
                    repository.set("matches", String.valueOf(match.get("id")), match);
                    matches.add(match);
                }
            }
        }
        return matches;
    }

    static Map<String, Object> evaluate(
            Map<String, Object> listing,
            Map<String, Object> requirement,
            BigDecimal availableQuantity,
            String listingDistrict) {
        if (!DataValues.normalize(String.valueOf(listing.get("crop")))
                .equals(DataValues.normalize(String.valueOf(requirement.get("crop"))))) {
            return null;
        }
        if (availableQuantity.compareTo(DataValues.decimal(requirement.get("quantity"))) < 0
                || !DataValues.normalize(String.valueOf(listing.get("unit")))
                .equals(DataValues.normalize(String.valueOf(requirement.get("unit"))))) {
            return null;
        }
        if (!DataValues.normalize(listingDistrict)
                .equals(DataValues.normalize(String.valueOf(requirement.get("location"))))) {
            return null;
        }

        BigDecimal price = DataValues.decimal(listing.get("price"));
        BigDecimal targetPrice = DataValues.decimal(requirement.get("targetPrice"));
        if (price.compareTo(targetPrice.multiply(PRICE_TOLERANCE)) > 0) {
            return null;
        }
        String quality = String.valueOf(requirement.getOrDefault("quality", ""));
        String grade = String.valueOf(listing.getOrDefault("grade", ""));
        if (!qualityMeets(grade, quality)) {
            return null;
        }

        List<String> reasons = new ArrayList<>(List.of(
                "same crop",
                "same unit",
                "same district",
                "available quantity covers requirement",
                "quality meets requirement"));
        BigDecimal differencePercent = price.subtract(targetPrice)
                .max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(targetPrice, 1, RoundingMode.HALF_UP);
        reasons.add("price within " + differencePercent.toPlainString() + "%");

        String id = UUID.nameUUIDFromBytes(
                (listing.get("id") + ":" + requirement.get("id")).getBytes(StandardCharsets.UTF_8)).toString();
        return DataValues.map(
                "id", id,
                "listingId", listing.get("id"),
                "requirementId", requirement.get("id"),
                "score", score(availableQuantity, requirement, price, targetPrice, grade, quality),
                "reasons", reasons,
                "status", "OPEN",
                "updatedAt", DataValues.now());
    }

    private static int score(
            BigDecimal availableQuantity,
            Map<String, Object> requirement,
            BigDecimal price,
            BigDecimal targetPrice,
            String grade,
            String quality) {
        BigDecimal requestedQuantity = DataValues.decimal(requirement.get("quantity"));
        int quantityScore = BigDecimal.valueOf(15)
                .multiply(requestedQuantity)
                .divide(availableQuantity, 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(15))
                .intValue();
        BigDecimal overTargetPercent = price.subtract(targetPrice)
                .max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(targetPrice, 4, RoundingMode.HALF_UP);
        int priceScore = BigDecimal.valueOf(20)
                .subtract(overTargetPercent.multiply(new BigDecimal("1.5")))
                .max(BigDecimal.valueOf(5))
                .intValue();
        int availableGradeRank = gradeRank(grade);
        int requestedGradeRank = gradeRank(quality);
        int qualityScore = quality.isBlank() ? 5
                : availableGradeRank == requestedGradeRank ? 10 : 7;
        return 25 + 10 + 20 + quantityScore + priceScore + qualityScore;
    }

    private static boolean qualityMeets(String listingGrade, String requestedQuality) {
        if (requestedQuality.isBlank()) {
            return true;
        }
        int availableRank = gradeRank(listingGrade);
        int requestedRank = gradeRank(requestedQuality);
        if (availableRank >= 0 && requestedRank >= 0) {
            return availableRank >= requestedRank;
        }
        return DataValues.normalize(listingGrade).equals(DataValues.normalize(requestedQuality));
    }

    private static int gradeRank(String grade) {
        return switch (DataValues.normalize(grade)) {
            case "a", "grade a", "premium", "1" -> 3;
            case "b", "grade b", "standard", "2" -> 2;
            case "c", "grade c", "basic", "3" -> 1;
            default -> -1;
        };
    }

    private UserRole role(Actor actor) {
        Map<String, Object> user = repository.find("users", actor.uid())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "profile_required",
                        "Create a farmer or buyer profile to view matches."));
        UserRole role = UserService.role(user);
        if (role == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "profile_required",
                    "Create a farmer or buyer profile to view matches.");
        }
        if ("DISABLED".equals(user.get("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This account is disabled.");
        }
        return role;
    }
}
