package com.raithamarga.backend.dto;

import com.raithamarga.backend.model.DealStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record FarmerProfileRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Size(max = 100) String village,
            @NotBlank @Size(max = 100) String taluk,
            @NotBlank @Size(max = 100) String district,
            @NotBlank @Size(max = 100) String state,
            @NotNull @PositiveOrZero BigDecimal landAcres) {
    }

    public record BuyerProfileRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Size(max = 160) String businessName,
            @NotBlank @Size(max = 100) String district) {
    }

    public record ListingRequest(
            @NotBlank @Size(max = 100) String crop,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank @Size(max = 30) String unit,
            @Size(max = 40) String grade,
            @NotNull @Positive BigDecimal price,
            @NotBlank @Size(max = 100) String district,
            @Size(max = 100) String taluk,
            @Size(max = 100) String village,
            @Size(max = 100) String state,
            @NotNull @FutureOrPresent LocalDate availabilityDate) {
    }

    /** Produce the team lists on behalf of a farmer. The availability date is optional (defaults to today). */
    public record AdminProduceRequest(
            @NotBlank @Size(max = 100) String crop,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank @Size(max = 30) String unit,
            @Size(max = 40) String grade,
            @NotNull @Positive BigDecimal price,
            LocalDate availabilityDate) {
    }

    /**
     * A farmer added by the RaithaMarga team (for example from a WhatsApp message).
     * Only a name and phone number are required: no email and no Firebase login.
     */
    public record AdminFarmerRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{10,20}$", message = "must be a valid phone number")
            String phone,
            @Size(max = 100) String village,
            @Size(max = 100) String taluk,
            @NotBlank @Size(max = 100) String district,
            @Size(max = 100) String state,
            @Size(max = 500) String notes,
            @Valid AdminProduceRequest produce) {
    }

    public record RequirementRequest(
            @NotBlank @Size(max = 100) String crop,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank @Size(max = 30) String unit,
            @Size(max = 40) String quality,
            @NotBlank @Size(max = 100) String location,
            @NotNull @Positive BigDecimal targetPrice) {
    }

    public record DealCreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String listingId,
            @NotNull @Positive BigDecimal quantity,
            @Positive BigDecimal price) {
    }

    public record CheckoutLineRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String listingId,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @Positive BigDecimal expectedPrice) {
    }

    public record CheckoutRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{8,100}$") String idempotencyKey,
            @NotNull @Size(min = 1, max = 100) List<@Valid CheckoutLineRequest> items) {
    }

    public record BidCreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String listingId,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @Positive BigDecimal offerPrice) {
    }

    public record BidActionRequest(
            @NotBlank @Pattern(regexp = "ACCEPT|REJECT|COUNTER|ACCEPT_COUNTER|REJECT_COUNTER") String action,
            @DecimalMin(value = "0.01") BigDecimal counterPrice) {
    }

    public record DealStatusRequest(@NotNull DealStatus status) {
    }

    public record AdminVerificationUpdateRequest(
            @NotBlank @Pattern(regexp = "PENDING|VERIFIED|REJECTED") String status,
            @Size(max = 500) String note) {
    }

    public record AdminUserStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {
    }

    public record ContactRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "(?i)email|phone", message = "must be email or phone")
            String contactPreference,
            @NotBlank @Size(max = 254) String contactValue,
            @NotBlank @Size(max = 100) String topic,
            @NotBlank @Size(max = 5000) String message) {

        public boolean hasValidContactValue() {
            if ("email".equalsIgnoreCase(contactPreference)) {
                return contactValue.matches("(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$");
            }
            return contactValue.matches("^\\+?[0-9 ()-]{7,20}$");
        }
    }
}
