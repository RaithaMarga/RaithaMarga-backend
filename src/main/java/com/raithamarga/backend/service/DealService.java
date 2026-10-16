package com.raithamarga.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
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
import java.util.List;
import java.util.Map;
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
                String now = DataValues.now();
                Map<String, Object> deal = DataValues.map(
                        "farmerId", farmerId,
                        "buyerId", actor.uid(),
                        "listingId", request.listingId(),
                        "quantity", DataValues.number(request.quantity()),
                        "price", DataValues.number(unitPrice),
                        "status", DealStatus.INTERESTED.name(),
                        "createdAt", now,
                        "updatedAt", now);
                listing.put("reservedQuantity", DataValues.number(reserved.add(request.quantity())));
                listing.put("updatedAt", now);
                transaction.set(listingReference, listing);
                transaction.set(dealReference, deal);
                deal.put("id", dealReference.getId());
                return deal;
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

    public Map<String, Object> updateStatus(Actor actor, String dealId, DealStatus nextStatus) {
        users.me(actor);
        DocumentReference dealReference = firestore.collection("deals").document(dealId);
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot dealSnapshot = transaction.get(dealReference).get();
                if (!dealSnapshot.exists()) {
                    throw notFound();
                }
                Map<String, Object> deal = new LinkedHashMap<>(dealSnapshot.getData());
                if (!isParty(actor, deal) && !isAdminForManagedFarmer(actor, deal)) {
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
                deal.put("id", dealId);
                return deal;
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
                .toList();
    }

    public Map<String, Object> get(Actor actor, String dealId) {
        users.me(actor);
        Map<String, Object> deal = repository.find("deals", dealId).orElseThrow(this::notFound);
        if (!isParty(actor, deal)) {
            throw forbidden("Only the farmer or buyer in this deal can view it.");
        }
        deal.put("id", dealId);
        return deal;
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
}
