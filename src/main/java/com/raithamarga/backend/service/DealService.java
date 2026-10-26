package com.raithamarga.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.raithamarga.backend.dto.ApiDtos.CheckoutLineRequest;
import com.raithamarga.backend.dto.ApiDtos.CheckoutRequest;
import com.raithamarga.backend.dto.ApiDtos.DealCreateRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.DealStatus;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class DealService {

    private final FirestoreRepository repository;
    private final Firestore firestore;
    private final UserService users;

    public DealService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.firestore = repository.firestore();
        this.users = users;
    }

    public Map<String, Object> create(Actor actor, DealCreateRequest request) {
        users.requireRole(actor, UserRole.BUYER);
        Map<String, Object> buyer = repository.find("buyers", actor.uid())
                .orElseThrow(() -> forbidden("Complete your buyer profile before opening a deal."));
        if (!"VERIFIED".equals(buyer.get("verificationStatus"))) {
            throw forbidden("Only manually verified buyers can open deals.");
        }

        DocumentReference listingReference = firestore.collection("listings").document(request.listingId());
        DocumentReference dealReference = firestore.collection("deals").document();
        DocumentReference auditReference = firestore.collection("auditLogs").document();
        String buyerName = actorName(actor, "Buyer");
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot listingSnapshot = transaction.get(listingReference).get();
                if (!listingSnapshot.exists()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "listing_not_found", "Listing was not found.");
                }
                Map<String, Object> listing = new LinkedHashMap<>(listingSnapshot.getData());
                if (!"ACTIVE".equals(listing.get("status"))) {
                    throw conflict("This listing is no longer available.");
                }
                String farmerId = String.valueOf(listing.get("farmerId"));
                if (actor.uid().equals(farmerId)) {
                    throw forbidden("You cannot open a deal on your own listing.");
                }
                BigDecimal total = DataValues.decimal(listing.get("quantity"));
                BigDecimal reserved = DataValues.decimal(listing.get("reservedQuantity"));
                BigDecimal sold = DataValues.decimal(listing.get("soldQuantity"));
                BigDecimal available = total.subtract(reserved).subtract(sold);
                if (request.quantity().compareTo(available) > 0) {
                    throw conflict("Requested quantity exceeds the available quantity.");
                }

                BigDecimal unitPrice = request.price() == null
                        ? DataValues.decimal(listing.get("price")) : request.price();
                String farmerName = String.valueOf(listing.getOrDefault("farmerName", "Farmer"));
                String now = DataValues.now();
                Map<String, Object> deal = DataValues.map(
                        "farmerId", farmerId,
                        "buyerId", actor.uid(),
                        "listingId", request.listingId(),
                        "crop", listing.get("crop"),
                        "unit", listing.get("unit"),
                        "farmerName", farmerName,
                        "buyerName", buyerName,
                        "quantity", DataValues.number(request.quantity()),
                        "price", DataValues.number(unitPrice),
                        "paymentStatus", "PENDING",
                        "status", DealStatus.INTERESTED.name(),
                        "createdAt", now,
                        "updatedAt", now);
                listing.put("reservedQuantity", DataValues.number(reserved.add(request.quantity())));
                listing.put("updatedAt", now);
                transaction.set(listingReference, listing);
                transaction.set(dealReference, deal);
                if (Boolean.TRUE.equals(listing.get("managed"))) {
                    String farmerName = String.valueOf(listing.getOrDefault("farmerName", "Team-added farmer"));
                    transaction.create(auditReference, DataValues.map(
                            "adminUid", actor.uid(),
                            "adminName", buyerName,
                            "action", "MANAGED_DEAL_INTEREST",
                            "targetId", dealReference.getId(),
                            "targetUid", farmerId,
                            "targetName", farmerName,
                            "targetRole", UserRole.FARMER.name(),
                            "buyerName", buyerName,
                            "crop", listing.get("crop"),
                            "quantity", DataValues.number(request.quantity()),
                            "unit", listing.get("unit"),
                            "note", buyerName + " requested " + DataValues.number(request.quantity())
                                    + " " + listing.get("unit") + " of " + listing.get("crop")
                                    + " from " + farmerName + ".",
                            "at", now));
                }
                deal.put("id", dealReference.getId());
                return safeDeal(deal);
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

    public List<Map<String, Object>> checkout(Actor actor, CheckoutRequest request) {
        users.requireRole(actor, UserRole.BUYER);
        Map<String, Object> buyer = repository.find("buyers", actor.uid())
                .orElseThrow(() -> forbidden("Complete your buyer profile before checkout."));
        if (!"VERIFIED".equals(buyer.get("verificationStatus"))) {
            throw forbidden("Only manually verified buyers can check out.");
        }

        Set<String> listingIds = new HashSet<>();
        for (CheckoutLineRequest item : request.items()) {
            if (!listingIds.add(item.listingId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_listing",
                        "A listing can appear only once in a checkout.");
            }
        }

        String lineSignature = request.items().stream()
                .map(item -> item.listingId() + ":" + item.quantity().stripTrailingZeros().toPlainString()
                        + ":" + item.expectedPrice().stripTrailingZeros().toPlainString())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        String now = DataValues.now();
        String buyerName = actorName(actor, "Buyer");
        DocumentReference receiptReference = firestore.collection("checkoutRequests")
                .document(actor.uid() + "_" + request.idempotencyKey());
        List<DocumentReference> listingReferences = request.items().stream()
                .map(item -> firestore.collection("listings").document(item.listingId())).toList();
        List<DocumentReference> dealReferences = request.items().stream()
                .map(item -> firestore.collection("deals").document()).toList();
        List<DocumentReference> auditReferences = request.items().stream()
                .map(item -> firestore.collection("auditLogs").document()).toList();

        try {
            CheckoutOutcome outcome = firestore.runTransaction(transaction -> {
                DocumentSnapshot receipt = transaction.get(receiptReference).get();
                if (receipt.exists()) {
                    if (!lineSignature.equals(receipt.getString("lineSignature"))) {
                        throw conflict("This checkout key was already used for different cart contents.");
                    }
                    @SuppressWarnings("unchecked")
                    List<String> previousDealIds = (List<String>) receipt.get("dealIds");
                    return new CheckoutOutcome(previousDealIds, true);
                }

                List<Map<String, Object>> listings = new ArrayList<>();
                for (int index = 0; index < listingReferences.size(); index++) {
                    DocumentSnapshot snapshot = transaction.get(listingReferences.get(index)).get();
                    if (!snapshot.exists()) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "listing_not_found",
                                "A listing in this cart was not found.");
                    }
                    Map<String, Object> listing = new LinkedHashMap<>(snapshot.getData());
                    if (!"ACTIVE".equals(listing.get("status"))) {
                        throw conflict("A listing in this cart is no longer available.");
                    }
                    BigDecimal currentPrice = DataValues.decimal(listing.get("price"));
                    if (currentPrice.compareTo(request.items().get(index).expectedPrice()) != 0) {
                        throw conflict("The price of " + listing.get("crop")
                                + " has changed. Refresh the cart and review the current price.");
                    }
                    BigDecimal available = DataValues.decimal(listing.get("quantity"))
                            .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                            .subtract(DataValues.decimal(listing.get("soldQuantity")));
                    if (request.items().get(index).quantity().compareTo(available) > 0) {
                        throw conflict("Requested quantity for " + listing.get("crop")
                                + " exceeds the available quantity.");
                    }
                    listings.add(listing);
                }

                List<String> dealIds = new ArrayList<>();
                List<Map<String, Object>> createdDeals = new ArrayList<>();
                for (int index = 0; index < listings.size(); index++) {
                    CheckoutLineRequest item = request.items().get(index);
                    Map<String, Object> listing = listings.get(index);
                    BigDecimal quantity = item.quantity();
                    BigDecimal reserved = DataValues.decimal(listing.get("reservedQuantity"));
                    listing.put("reservedQuantity", DataValues.number(reserved.add(quantity)));
                    listing.put("updatedAt", now);
                    transaction.set(listingReferences.get(index), listing);

                    String farmerId = String.valueOf(listing.get("farmerId"));
                    String farmerName = String.valueOf(listing.getOrDefault("farmerName", "Farmer"));
                    Map<String, Object> deal = DataValues.map(
                            "farmerId", farmerId,
                            "buyerId", actor.uid(),
                            "listingId", item.listingId(),
                            "crop", listing.get("crop"),
                            "unit", listing.get("unit"),
                            "farmerName", farmerName,
                            "buyerName", buyerName,
                            "quantity", DataValues.number(quantity),
                            "price", DataValues.number(item.expectedPrice()),
                            "paymentStatus", "PENDING",
                            "status", DealStatus.INTERESTED.name(),
                            "createdAt", now,
                            "updatedAt", now);
                    transaction.create(dealReferences.get(index), deal);
                    deal.put("id", dealReferences.get(index).getId());
                    dealIds.add(dealReferences.get(index).getId());
                    createdDeals.add(safeDeal(deal));

                    if (Boolean.TRUE.equals(listing.get("managed"))) {
                        String managedFarmerName = farmerName;
                        transaction.create(auditReferences.get(index), DataValues.map(
                                "adminUid", actor.uid(),
                                "adminName", buyerName,
                                "action", "MANAGED_DEAL_INTEREST",
                                "targetId", dealReferences.get(index).getId(),
                                "targetUid", farmerId,
                                "targetName", managedFarmerName,
                                "targetRole", UserRole.FARMER.name(),
                                "buyerName", buyerName,
                                "crop", listing.get("crop"),
                                "quantity", DataValues.number(quantity),
                                "unit", listing.get("unit"),
                                "note", buyerName + " requested " + DataValues.number(quantity) + " "
                                        + listing.get("unit") + " of " + listing.get("crop")
                                        + " from " + managedFarmerName + ".",
                                "at", now));
                    }
                }
                transaction.create(receiptReference, DataValues.map(
                        "buyerId", actor.uid(),
                        "lineSignature", lineSignature,
                        "dealIds", dealIds,
                        "createdAt", now));
                return new CheckoutOutcome(dealIds, false, createdDeals);
            }).get();

            if (!outcome.alreadyProcessed()) {
                return outcome.deals();
            }
            return outcome.dealIds().stream()
                    .map(id -> repository.find("deals", id).map(deal -> {
                        deal.put("id", id);
                        return safeDeal(deal);
                    }).orElseThrow(this::notFound))
                    .toList();
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

    public Map<String, Object> updateStatus(Actor actor, String dealId, DealStatus nextStatus) {
        users.me(actor);
        DocumentReference dealReference = firestore.collection("deals").document(dealId);
        DocumentReference auditReference = firestore.collection("auditLogs").document();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot dealSnapshot = transaction.get(dealReference).get();
                if (!dealSnapshot.exists()) {
                    throw notFound();
                }
                Map<String, Object> deal = new LinkedHashMap<>(dealSnapshot.getData());
                boolean managedAdminAction = !isParty(actor, deal) && isAdminForManagedFarmer(actor, deal);
                if (!isParty(actor, deal) && !managedAdminAction) {
                    throw forbidden("Only the farmer or buyer in this deal can update it.");
                }
                DealStatus current = DealStatus.valueOf(String.valueOf(deal.get("status")));
                DealStatusMachine.requireTransition(current, nextStatus);
                String listingId = String.valueOf(deal.get("listingId"));
                DocumentReference listingReference = firestore.collection("listings").document(listingId);
                Map<String, Object> listing = null;
                if (nextStatus == DealStatus.COMPLETED
                        || nextStatus == DealStatus.CANCELLED
                        || nextStatus == DealStatus.FAILED) {
                    DocumentSnapshot listingSnapshot = transaction.get(listingReference).get();
                    if (!listingSnapshot.exists()) {
                        throw new ApiException(HttpStatus.CONFLICT, "listing_missing",
                                "The listing linked to this deal no longer exists.");
                    }
                    listing = new LinkedHashMap<>(listingSnapshot.getData());
                }

                String now = DataValues.now();
                if (listing != null) {
                    BigDecimal quantity = DataValues.decimal(deal.get("quantity"));
                    BigDecimal reserved = DataValues.decimal(listing.get("reservedQuantity"));
                    listing.put("reservedQuantity", DataValues.number(reserved.subtract(quantity).max(BigDecimal.ZERO)));
                    if (nextStatus == DealStatus.COMPLETED) {
                        BigDecimal sold = DataValues.decimal(listing.get("soldQuantity")).add(quantity);
                        listing.put("soldQuantity", DataValues.number(sold));
                        if (sold.compareTo(DataValues.decimal(listing.get("quantity"))) >= 0) {
                            listing.put("status", "SOLD");
                        }
                    }
                    listing.put("updatedAt", now);
                    transaction.set(listingReference, listing);
                }
                deal.put("status", nextStatus.name());
                deal.put("updatedAt", now);
                transaction.set(dealReference, deal);
                if (managedAdminAction) {
                    String farmerId = String.valueOf(deal.get("farmerId"));
                    String farmerName = repository.find("farmers", farmerId)
                            .map(farmer -> String.valueOf(farmer.getOrDefault("name", farmerId)))
                            .orElse(farmerId);
                    String adminName = actorName(actor, "Admin");
                    transaction.create(auditReference, DataValues.map(
                            "adminUid", actor.uid(),
                            "adminName", adminName,
                            "action", "MANAGED_DEAL_STATUS_CHANGED",
                            "targetId", dealId,
                            "targetUid", farmerId,
                            "targetName", farmerName,
                            "targetRole", UserRole.FARMER.name(),
                            "fromStatus", current.name(),
                            "toStatus", nextStatus.name(),
                            "note", "Deal " + dealId + " status changed from " + current.name()
                                    + " to " + nextStatus.name() + ".",
                            "at", now));
                }
                deal.put("id", dealId);
                return safeDeal(deal);
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

    public List<Map<String, Object>> list(Actor actor) {
        users.me(actor);
        return repository.list("deals").stream()
                .filter(deal -> isParty(actor, deal))
                .sorted((left, right) -> String.valueOf(right.get("createdAt"))
                        .compareTo(String.valueOf(left.get("createdAt"))))
                .map(this::safeDeal)
                .toList();
    }

    public Map<String, Object> get(Actor actor, String dealId) {
        users.me(actor);
        Map<String, Object> deal = repository.find("deals", dealId).orElseThrow(this::notFound);
        if (!isParty(actor, deal)) {
            throw forbidden("Only the farmer or buyer in this deal can view it.");
        }
        deal.put("id", dealId);
        return safeDeal(deal);
    }

    /**
     * Farmers added by the team have no login, so an admin acts for them on deals
     * (confirm, in progress, complete, cancel).
     */
    private boolean isAdminForManagedFarmer(Actor actor, Map<String, Object> deal) {
        boolean admin = repository.find("users", actor.uid())
                .map(user -> "ADMIN".equals(String.valueOf(user.get("role")))
                        && !"DISABLED".equals(String.valueOf(user.get("status"))))
                .orElse(false);
        return admin && repository.find("farmers", String.valueOf(deal.get("farmerId")))
                .map(farmer -> Boolean.TRUE.equals(farmer.get("managed")))
                .orElse(false);
    }

    private boolean isParty(Actor actor, Map<String, Object> deal) {
        return actor.uid().equals(deal.get("farmerId")) || actor.uid().equals(deal.get("buyerId"));
    }

    private Map<String, Object> safeDeal(Map<String, Object> deal) {
        Map<String, Object> safe = new LinkedHashMap<>(deal);
        safe.remove("farmerPhone");
        safe.remove("phone");
        if (String.valueOf(safe.get("farmerId")).startsWith(ManagedFarmerService.ID_PREFIX)) {
            safe.remove("farmerId");
        }
        return safe;
    }

    private String actorName(Actor actor, String fallback) {
        return repository.find("users", actor.uid())
                .map(user -> user.get("name"))
                .filter(name -> !String.valueOf(name).isBlank())
                .map(String::valueOf)
                .orElseGet(() -> actor.displayName() == null || actor.displayName().isBlank()
                        ? fallback : actor.displayName());
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    private ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "deal_conflict", message);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "deal_not_found", "Deal was not found.");
    }

    private ApiException databaseUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "The data service is temporarily unavailable.");
    }

    private record CheckoutOutcome(List<String> dealIds, boolean alreadyProcessed, List<Map<String, Object>> deals) {
        private CheckoutOutcome(List<String> dealIds, boolean alreadyProcessed) {
            this(dealIds, alreadyProcessed, List.of());
        }
    }
}
