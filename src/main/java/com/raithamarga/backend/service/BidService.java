package com.raithamarga.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.raithamarga.backend.dto.ApiDtos.BidActionRequest;
import com.raithamarga.backend.dto.ApiDtos.BidCreateRequest;
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
public class BidService {

    private final FirestoreRepository repository;
    private final Firestore firestore;
    private final UserService users;

    public BidService(FirestoreRepository repository, UserService users) {
        this.repository = repository;
        this.firestore = repository.firestore();
        this.users = users;
    }

    public Map<String, Object> create(Actor actor, BidCreateRequest request) {
        users.requireRole(actor, UserRole.BUYER);
        Map<String, Object> buyer = repository.find("buyers", actor.uid())
                .orElseThrow(() -> forbidden("Complete your buyer profile before making an offer."));
        if (!"VERIFIED".equals(buyer.get("verificationStatus"))) {
            throw forbidden("Only manually verified buyers can make offers.");
        }
        String buyerName = actorName(actor, "Buyer");
        DocumentReference listingReference = firestore.collection("listings").document(request.listingId());
        DocumentReference bidReference = firestore.collection("bids").document();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot listingSnapshot = transaction.get(listingReference).get();
                if (!listingSnapshot.exists()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "listing_not_found", "Listing was not found.");
                }
                Map<String, Object> listing = listingSnapshot.getData();
                if (!"ACTIVE".equals(listing.get("status"))) {
                    throw conflict("This listing is no longer available.");
                }
                if (Boolean.TRUE.equals(listing.get("managed"))) {
                    throw forbidden("Offers are not available for team-managed farmers because they do not have marketplace logins.");
                }
                String farmerId = String.valueOf(listing.get("farmerId"));
                if (farmerId.equals(actor.uid())) {
                    throw forbidden("You cannot make an offer on your own listing.");
                }
                BigDecimal available = DataValues.decimal(listing.get("quantity"))
                        .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                        .subtract(DataValues.decimal(listing.get("soldQuantity")));
                if (request.quantity().compareTo(available) > 0) {
                    throw conflict("Requested quantity exceeds the available quantity.");
                }
                String now = DataValues.now();
                Map<String, Object> bid = DataValues.map(
                        "buyerId", actor.uid(),
                        "buyerName", buyerName,
                        "farmerId", farmerId,
                        "farmerName", listing.get("farmerName"),
                        "listingId", request.listingId(),
                        "crop", listing.get("crop"),
                        "unit", listing.get("unit"),
                        "quantity", DataValues.number(request.quantity()),
                        "askingPrice", listing.get("price"),
                        "offerPrice", DataValues.number(request.offerPrice()),
                        "status", "OFFERED",
                        "createdAt", now,
                        "updatedAt", now);
                transaction.create(bidReference, bid);
                bid.put("id", bidReference.getId());
                return bid;
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
        Map<String, Object> user = users.me(actor);
        UserRole role = UserService.role(user);
        if (role != UserRole.BUYER && role != UserRole.FARMER) {
            throw forbidden("Only buyers and farmers can view offers.");
        }
        String partyField = role == UserRole.BUYER ? "buyerId" : "farmerId";
        return repository.list("bids").stream()
                .filter(bid -> actor.uid().equals(bid.get(partyField)))
                .sorted((left, right) -> String.valueOf(right.get("createdAt"))
                        .compareTo(String.valueOf(left.get("createdAt"))))
                .map(bid -> {
                    bid.remove("buyerPhone");
                    bid.remove("farmerPhone");
                    return bid;
                })
                .toList();
    }

    public Map<String, Object> act(Actor actor, String bidId, BidActionRequest request) {
        Map<String, Object> user = users.me(actor);
        UserRole role = UserService.role(user);
        String action = request.action();
        if (role != UserRole.FARMER && role != UserRole.BUYER) {
            throw forbidden("Only buyers and farmers can update offers.");
        }
        if (("COUNTER".equals(action) && role != UserRole.FARMER)
                || (("ACCEPT_COUNTER".equals(action) || "REJECT_COUNTER".equals(action)) && role != UserRole.BUYER)
                || (("ACCEPT".equals(action) || "REJECT".equals(action)) && role != UserRole.FARMER)) {
            throw forbidden("This offer action is not available to your account.");
        }
        if ("COUNTER".equals(action) && request.counterPrice() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "counter_price_required",
                    "A counter-offer price is required.");
        }

        DocumentReference bidReference = firestore.collection("bids").document(bidId);
        DocumentReference dealReference = firestore.collection("deals").document();
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot bidSnapshot = transaction.get(bidReference).get();
                if (!bidSnapshot.exists()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "bid_not_found", "Offer was not found.");
                }
                Map<String, Object> bid = new LinkedHashMap<>(bidSnapshot.getData());
                boolean buyer = actor.uid().equals(bid.get("buyerId"));
                boolean farmer = actor.uid().equals(bid.get("farmerId"));
                if ((role == UserRole.BUYER && !buyer) || (role == UserRole.FARMER && !farmer)) {
                    throw forbidden("You can only update offers for your own account.");
                }
                String status = String.valueOf(bid.get("status"));
                boolean createsDeal = "ACCEPT".equals(action) || "ACCEPT_COUNTER".equals(action);
                if ("OFFERED".equals(status) && ("ACCEPT".equals(action) || "REJECT".equals(action)
                        || "COUNTER".equals(action))) {
                    if ("ACCEPT".equals(action)) {
                        createAgreedDeal(transaction, bid, dealReference, DataValues.decimal(bid.get("offerPrice")));
                        bid.put("status", "ACCEPTED");
                        bid.put("agreedPrice", bid.get("offerPrice"));
                        bid.put("dealId", dealReference.getId());
                    } else if ("REJECT".equals(action)) {
                        bid.put("status", "REJECTED");
                    } else {
                        bid.put("counterPrice", DataValues.number(request.counterPrice()));
                        bid.put("status", "COUNTERED");
                    }
                } else if ("COUNTERED".equals(status)
                        && ("ACCEPT_COUNTER".equals(action) || "REJECT_COUNTER".equals(action))) {
                    if ("ACCEPT_COUNTER".equals(action)) {
                        BigDecimal counterPrice = DataValues.decimal(bid.get("counterPrice"));
                        createAgreedDeal(transaction, bid, dealReference, counterPrice);
                        bid.put("status", "ACCEPTED");
                        bid.put("agreedPrice", DataValues.number(counterPrice));
                        bid.put("dealId", dealReference.getId());
                    } else {
                        bid.put("status", "REJECTED");
                    }
                } else {
                    throw conflict("This offer has already been answered or cannot take that action.");
                }
                String now = DataValues.now();
                bid.put("updatedAt", now);
                transaction.set(bidReference, bid);
                bid.put("id", bidId);
                return bid;
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

    private void createAgreedDeal(
            com.google.cloud.firestore.Transaction transaction,
            Map<String, Object> bid,
            DocumentReference dealReference,
            BigDecimal agreedPrice) throws Exception {
        DocumentReference listingReference = firestore.collection("listings")
                .document(String.valueOf(bid.get("listingId")));
        DocumentSnapshot listingSnapshot = transaction.get(listingReference).get();
        if (!listingSnapshot.exists()) {
            throw conflict("The listing for this offer no longer exists.");
        }
        Map<String, Object> listing = new LinkedHashMap<>(listingSnapshot.getData());
        if (!"ACTIVE".equals(listing.get("status"))) {
            throw conflict("The listing for this offer is no longer available.");
        }
        BigDecimal quantity = DataValues.decimal(bid.get("quantity"));
        BigDecimal available = DataValues.decimal(listing.get("quantity"))
                .subtract(DataValues.decimal(listing.get("reservedQuantity")))
                .subtract(DataValues.decimal(listing.get("soldQuantity")));
        if (quantity.compareTo(available) > 0) {
            throw conflict("The listing no longer has enough available quantity for this offer.");
        }
        String now = DataValues.now();
        listing.put("reservedQuantity",
                DataValues.number(DataValues.decimal(listing.get("reservedQuantity")).add(quantity)));
        listing.put("updatedAt", now);
        transaction.set(listingReference, listing);
        transaction.create(dealReference, DataValues.map(
                "farmerId", bid.get("farmerId"),
                "buyerId", bid.get("buyerId"),
                "listingId", bid.get("listingId"),
                "crop", bid.get("crop"),
                "unit", bid.get("unit"),
                "farmerName", bid.get("farmerName"),
                "buyerName", bid.get("buyerName"),
                "quantity", DataValues.number(quantity),
                "price", DataValues.number(agreedPrice),
                "agreedPrice", DataValues.number(agreedPrice),
                "paymentStatus", "PENDING",
                "status", DealStatus.CONFIRMED.name(),
                "createdAt", now,
                "updatedAt", now));
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
        return new ApiException(HttpStatus.CONFLICT, "bid_conflict", message);
    }

    private ApiException databaseUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "The data service is temporarily unavailable.");
    }
}
