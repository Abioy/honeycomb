package com.nearinfinity.honeycomb;

import java.util.UUID;

public class RowNotFoundException extends RuntimeException {
    private UUID uuid;

    public RowNotFoundException(UUID uuid) {
        this.uuid = uuid;
    }
}