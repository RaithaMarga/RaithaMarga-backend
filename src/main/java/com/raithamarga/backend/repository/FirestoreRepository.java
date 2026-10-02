package com.raithamarga.backend.repository;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.raithamarga.backend.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Repository
public class FirestoreRepository {

    private final Firestore firestore;

    public FirestoreRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    public Optional<Map<String, Object>> find(String collection, String id) {
        try {
            DocumentSnapshot snapshot = firestore.collection(collection).document(id).get().get();
            return snapshot.exists() ? Optional.of(new LinkedHashMap<>(snapshot.getData())) : Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable(exception);
        } catch (ExecutionException exception) {
            throw databaseUnavailable(exception);
        }
    }

    public List<Map<String, Object>> list(String collection) {
        try {
            QuerySnapshot snapshot = firestore.collection(collection).get().get();
            List<Map<String, Object>> documents = new ArrayList<>();
            for (QueryDocumentSnapshot document : snapshot.getDocuments()) {
                Map<String, Object> data = new LinkedHashMap<>(document.getData());
                data.put("id", document.getId());
                documents.add(data);
            }
            return documents;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable(exception);
        } catch (ExecutionException exception) {
            throw databaseUnavailable(exception);
        }
    }

    public String create(String collection, Map<String, Object> data) {
        DocumentReference reference = firestore.collection(collection).document();
        set(reference, data);
        return reference.getId();
    }

    public void set(String collection, String id, Map<String, Object> data) {
        set(firestore.collection(collection).document(id), data);
    }

    public void set(DocumentReference reference, Map<String, Object> data) {
        try {
            reference.set(data).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable(exception);
        } catch (ExecutionException exception) {
            throw databaseUnavailable(exception);
        }
    }

    public void delete(String collection, String id) {
        try {
            firestore.collection(collection).document(id).delete().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseUnavailable(exception);
        } catch (ExecutionException exception) {
            throw databaseUnavailable(exception);
        }
    }

    public Firestore firestore() {
        return firestore;
    }

    private ApiException databaseUnavailable(Exception exception) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "The data service is temporarily unavailable.");
    }
}
