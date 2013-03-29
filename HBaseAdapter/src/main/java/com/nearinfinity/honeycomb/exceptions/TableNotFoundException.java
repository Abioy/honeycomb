package com.nearinfinity.honeycomb.exceptions;

public class TableNotFoundException extends RuntimeException {
    private String name;
    private long id;

    public TableNotFoundException(String name) {
        this.name = name;
    }

    public TableNotFoundException(Long tableId) {
        this.id = tableId;
    }
}
