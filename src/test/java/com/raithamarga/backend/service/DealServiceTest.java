package com.raithamarga.backend.service;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.raithamarga.backend.dto.ApiDtos.DealCreateRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.DealStatus;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class DealServiceTest {

    private final FirestoreRepository repository = mock(FirestoreRepository.class);
    private final UserService users = mock(UserService.class);
    private final Firestore firestore = mock(Firestore.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
    private final Transaction transaction = mock(Transaction.class);
    private final Actor buyer = new Actor("buyer-1", "buyer@example.test", "Buyer User");
    private final Actor admin = new Actor("admin-1", "admin@example.test", "Admin User");
    private DealService deals;

    @BeforeEach
    void setUp() {
        when(repository.firestore()).thenReturn(firestore);
        deals = new DealService(repository, users);
    }

    @Test
    void buyerInterestOnManagedListingCreatesAuditEntry() throws Exception {
        when(repository.find("buyers", buyer.uid())).thenReturn(Optional.of(Map.of("verificationStatus", "VERIFIED")));
        when(repository.find("users", buyer.uid())).thenReturn(Optional.of(Map.of("name", "Buyer Name")));
        DocumentReference listingReference = firestore.collection("listings").document("listing-1");
        DocumentSnapshot listingSnapshot = mock(DocumentSnapshot.class);
        when(listingSnapshot.exists()).thenReturn(true);
        when(listingSnapshot.getData()).thenReturn(Map.of(
                "farmerId", "managed-9876543210",
                "farmerName", "Farmer Name",
                "managed", true,
                "crop", "Onion",
                "unit", "kg",
                "quantity", 25D,
                "reservedQuantity", 0D,
                "soldQuantity", 0D,
                "price", 20D,
                "status", "ACTIVE"));
        when(transaction.get(listingReference)).thenReturn(ApiFutures.immediateFuture(listingSnapshot));
        runTransactionInline();

        Map<String, Object> result = deals.create(buyer,
                new DealCreateRequest("listing-1", new BigDecimal("3"), null));

        assertEquals("INTERESTED", result.get("status"));
        assertFalse(result.containsKey("farmerPhone"));
        assertFalse(result.containsKey("farmerId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transaction).create(any(DocumentReference.class), auditCaptor.capture());
        Map<String, Object> audit = auditCaptor.getValue();
        assertEquals(buyer.uid(), audit.get("adminUid"));
        assertEquals("Buyer Name", audit.get("adminName"));
        assertEquals("MANAGED_DEAL_INTEREST", audit.get("action"));
        assertEquals("Farmer Name", audit.get("targetName"));
        assertEquals("Buyer Name", audit.get("buyerName"));
        assertEquals("Onion", audit.get("crop"));
        assertEquals(3D, audit.get("quantity"));
    }

    @Test
    void adminCanUpdateDealForManagedFarmer() throws Exception {
        DocumentReference dealReference = firestore.collection("deals").document("deal-1");
        DocumentSnapshot dealSnapshot = mock(DocumentSnapshot.class);
        when(dealSnapshot.exists()).thenReturn(true);
        when(dealSnapshot.getData()).thenReturn(Map.of(
                "farmerId", "managed-9876543210",
                "buyerId", "buyer-1",
                "listingId", "listing-1",
                "quantity", 3D,
                "status", "INTERESTED"));
        when(transaction.get(dealReference)).thenReturn(ApiFutures.immediateFuture(dealSnapshot));
        when(repository.find("users", admin.uid())).thenReturn(Optional.of(Map.of("role", "ADMIN", "status", "ACTIVE", "name", "Admin Name")));
        when(repository.find("farmers", "managed-9876543210")).thenReturn(Optional.of(Map.of("managed", true, "name", "Farmer Name")));
        runTransactionInline();

        Map<String, Object> result = deals.updateStatus(admin, "deal-1", DealStatus.CONFIRMED);

        assertEquals("CONFIRMED", result.get("status"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transaction).create(any(DocumentReference.class), auditCaptor.capture());
        Map<String, Object> audit = auditCaptor.getValue();
        assertEquals("MANAGED_DEAL_STATUS_CHANGED", audit.get("action"));
        assertEquals("INTERESTED", audit.get("fromStatus"));
        assertEquals("CONFIRMED", audit.get("toStatus"));
        assertEquals("deal-1", audit.get("targetId"));
        assertEquals("Farmer Name", audit.get("targetName"));
    }

    @Test
    void adminCannotUpdateDealForSelfRegisteredFarmer() throws Exception {
        DocumentReference dealReference = firestore.collection("deals").document("deal-self");
        DocumentSnapshot dealSnapshot = mock(DocumentSnapshot.class);
        when(dealSnapshot.exists()).thenReturn(true);
        when(dealSnapshot.getData()).thenReturn(Map.of(
                "farmerId", "farmer-1",
                "buyerId", "buyer-1",
                "listingId", "listing-1",
                "status", "INTERESTED"));
        when(transaction.get(dealReference)).thenReturn(ApiFutures.immediateFuture(dealSnapshot));
        when(repository.find("users", admin.uid())).thenReturn(Optional.of(Map.of("role", "ADMIN", "status", "ACTIVE")));
        when(repository.find("farmers", "farmer-1")).thenReturn(Optional.of(Map.of("managed", false)));
        runTransactionInline();

        assertThrows(ApiException.class,
                () -> deals.updateStatus(admin, "deal-self", DealStatus.CONFIRMED));

        verify(transaction, never()).set(eq(dealReference), any());
        verify(transaction, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void nonAdminDealResponseNeverIncludesFarmerPhone() {
        Map<String, Object> storedDeal = new LinkedHashMap<>(Map.of(
                "farmerId", "managed-9876543210",
                "buyerId", buyer.uid(),
                "status", "INTERESTED"));
        storedDeal.put("farmerPhone", "+91 98765 43210");
        when(repository.find("deals", "deal-1")).thenReturn(Optional.of(storedDeal));

        Map<String, Object> result = deals.get(buyer, "deal-1");

        assertFalse(result.containsKey("farmerPhone"));
        assertFalse(result.containsKey("farmerId"));
        assertFalse(result.containsKey("phone"));
    }

    @Test
    void nonAdminListingResponseDoesNotExposeManagedFarmerPhoneOrId() {
        Map<String, Object> storedListing = new LinkedHashMap<>(Map.of(
                "id", "listing-1",
                "farmerId", "managed-9876543210",
                "managed", true,
                "status", "ACTIVE",
                "quantity", 10D,
                "reservedQuantity", 0D,
                "soldQuantity", 0D));
        storedListing.put("farmerPhone", "+91 98765 43210");
        when(repository.list("listings")).thenReturn(List.of(storedListing));
        when(users.me(buyer)).thenReturn(Map.of("role", "BUYER"));

        Map<String, Object> result = new ListingService(repository, users).list(buyer, null, null).getFirst();

        assertFalse(result.containsKey("farmerId"));
        assertFalse(result.containsKey("farmerPhone"));
        assertFalse(result.containsKey("phone"));
    }

    private void runTransactionInline() throws Exception {
        doAnswer(invocation -> {
            Transaction.Function<Map<String, Object>> function = invocation.getArgument(0);
            try {
                return ApiFutures.immediateFuture(function.updateCallback(transaction));
            } catch (Exception exception) {
                return ApiFutures.immediateFailedFuture(exception);
            }
        }).when(firestore).runTransaction(any());
    }
}
