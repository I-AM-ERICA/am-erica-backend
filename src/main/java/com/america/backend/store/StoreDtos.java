package com.america.backend.store;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class StoreDtos {

    private StoreDtos() {
    }

    public record StoreRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String category,
            @NotBlank @Size(max = 50) String region,
            @Size(max = 255) String address) {
    }

    public record StoreResponse(
            Long id, String name, String category, String region, String address, Instant createdAt) {
        public static StoreResponse from(Store store) {
            return new StoreResponse(store.getId(), store.getName(), store.getCategory(),
                    store.getRegion(), store.getAddress(), store.getCreatedAt());
        }
    }
}
