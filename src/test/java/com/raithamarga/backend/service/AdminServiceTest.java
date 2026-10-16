package com.raithamarga.backend.service;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.raithamarga.backend.dto.ApiDtos.AdminUserStatusRequest;
import com.raithamarga.backend.dto.ApiDtos.AdminVerificationUpdateRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.model.UserRole;
import com.raithamarga.backend.repository.FirestoreRepository;
import com.raithamarga.backend.security.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.withSettings;

class AdminServiceTest {

    private final FirestoreRepository repository = mock(FirestoreRepository.class);
    private final UserService users = mock(UserService.class);
    private final Firestore firestore = mock(Firestore.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
    private final Transaction transaction = mock(Transaction.class);
    private final Actor adminActor = new Actor("admin-1", "admin@example.test", "Admin User");
    private AdminService admin;

    @BeforeEach
    void setUp() {
        when(repository.firestore()).thenReturn(firestore);
        admin = new AdminService(repository, users);
    }

    @Test
    void rejectsNonAdminBeforeReadingAdminData() {
        ApiException forbidden = new ApiException(
                org.springframework.http.HttpStatus.FORBIDDEN, "forbidden", "Admins only.");
        doThrow(forbidden).when(users).requireRole(adminActor, UserRole.ADMIN);

        assertThrows(ApiException.class, () -> admin.overview(adminActor));
        verify(repository, never()).list(any());
    }

    @Test
    void rejectsInvalidVerificationStatusAfterAuthorization() {
        assertThrows(ApiException.class, () -> admin.verifications(adminActor, null, "APPROVED"));

        verify(users).requireRole(adminActor, UserRole.ADMIN);
        verify(repository, never()).list(any());
    }

    @Test
    void adminCannotDisableTheirOwnAccount() throws Exception {
        DocumentReference userReference = firestore.collection("users").document("admin-1");
        DocumentSnapshot userSnapshot = mock(DocumentSnapshot.class);
        when(userSnapshot.exists()).thenReturn(true);
        when(userSnapshot.getData()).thenReturn(Map.of("uid", "admin-1", "role", "ADMIN", "status", "ACTIVE"));
        when(transaction.get(userReference)).thenReturn(ApiFutures.immediateFuture(userSnapshot));
        runTransactionInline();

        assertThrows(ApiException.class, () -> admin.updateUserStatus(
                adminActor, "admin-1", new AdminUserStatusRequest("DISABLED")));

        verify(transaction, never()).create(any(DocumentReference.class), any());
        verify(transaction, never()).set(eq(userReference), any());
    }

    @Test
    void verificationUpdateWritesAuditEntryInTheTransaction() throws Exception {
        DocumentReference profileReference = firestore.collection("buyers").document("buyer-1");
        DocumentReference userReference = firestore.collection("users").document("buyer-1");
        DocumentSnapshot profileSnapshot = mock(DocumentSnapshot.class);
        DocumentSnapshot userSnapshot = mock(DocumentSnapshot.class);
        when(profileSnapshot.exists()).thenReturn(true);
        when(profileSnapshot.getData()).thenReturn(Map.of(
                "uid", "buyer-1", "businessName", "Market Co", "verificationStatus", "PENDING"));
        when(userSnapshot.exists()).thenReturn(true);
        when(userSnapshot.getData()).thenReturn(Map.of(
                "uid", "buyer-1", "name", "Buyer", "email", "buyer@example.test"));
        when(transaction.get(profileReference)).thenReturn(ApiFutures.immediateFuture(profileSnapshot));
        when(transaction.get(userReference)).thenReturn(ApiFutures.immediateFuture(userSnapshot));
        runTransactionInline();

        Map<String, Object> result = admin.updateVerification(adminActor, "BUYER", "buyer-1",
                new AdminVerificationUpdateRequest("VERIFIED", " documents checked "));

        assertEquals("VERIFIED", result.get("verificationStatus"));
        assertEquals("documents checked", result.get("verificationNote"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transaction).create(any(DocumentReference.class), auditCaptor.capture());
        Map<String, Object> audit = auditCaptor.getValue();
        assertEquals("admin-1", audit.get("adminUid"));
        assertEquals("VERIFICATION_UPDATED", audit.get("action"));
        assertEquals("buyer-1", audit.get("targetUid"));
        assertEquals("BUYER", audit.get("targetRole"));
        assertEquals("documents checked", audit.get("note"));
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) audit.get("after");
        assertEquals("VERIFIED", after.get("verificationStatus"));
        verify(transaction).set(eq(profileReference), any());
    }

    @Test
    void adminDealsIncludeManagedFarmerAndBuyerDetails() {
        when(repository.list("deals")).thenReturn(List.of(Map.of(
                "id", "deal-1",
                "farmerId", "managed-9876543210",
                "buyerId", "buyer-1",
                "listingId", "listing-1",
                "quantity", 3D,
                "price", 20D,
                "status", "INTERESTED",
                "createdAt", "2026-10-16T10:00:00Z")));
        when(repository.list("farmers")).thenReturn(List.of(Map.of(
                "id", "managed-9876543210", "name", "Farmer Name",
                "phone", "9876543210", "managed", true)));
        when(repository.list("buyers")).thenReturn(List.of(Map.of("id", "buyer-1")));
        when(repository.list("listings")).thenReturn(List.of(Map.of(
                "id", "listing-1", "crop", "Onion", "unit", "kg", "managed", true)));
        when(repository.list("users")).thenReturn(List.of(Map.of(
                "id", "buyer-1", "name", "Buyer Name", "email", "buyer@example.test")));

        Map<String, Object> deal = admin.deals(adminActor).getFirst();

        assertEquals("Farmer Name", deal.get("farmerName"));
        assertEquals("9876543210", deal.get("farmerPhone"));
        assertEquals("Buyer Name", deal.get("buyerName"));
        assertEquals("buyer@example.test", deal.get("buyerContact"));
        assertEquals("Onion", deal.get("crop"));
        assertEquals("kg", deal.get("unit"));
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
