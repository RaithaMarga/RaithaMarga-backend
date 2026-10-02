package com.raithamarga.backend.service;

import com.raithamarga.backend.dto.ApiDtos.ContactRequest;
import com.raithamarga.backend.exception.ApiException;
import com.raithamarga.backend.repository.FirestoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ContactService {

    private final FirestoreRepository repository;

    public ContactService(FirestoreRepository repository) {
        this.repository = repository;
    }

    public Map<String, String> submit(ContactRequest request) {
        if (!request.hasValidContactValue()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_contact",
                    "Enter a valid email address or phone number for your selected contact method.");
        }
        Map<String, Object> message = DataValues.map(
                "fullName", request.fullName().trim(),
                "contactPreference", request.contactPreference().toLowerCase(java.util.Locale.ROOT),
                "contactValue", request.contactValue().trim(),
                "topic", request.topic().trim(),
                "message", request.message().trim(),
                "status", "NEW",
                "createdAt", DataValues.now());
        String id = repository.create("contactMessages", message);
        return Map.of("id", id, "message", "Your message has been received.");
    }
}
