package com.dataagent.lab.planner;

public class DataCatalogUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataCatalogUnavailableException(String message) {
        super(message);
    }
}
