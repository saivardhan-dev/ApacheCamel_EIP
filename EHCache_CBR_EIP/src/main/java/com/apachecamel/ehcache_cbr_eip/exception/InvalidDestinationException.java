package com.apachecamel.ehcache_cbr_eip.exception;

public class InvalidDestinationException extends RuntimeException {
    public InvalidDestinationException(String type) {
        super("Invalid destination type: " + type);
    }
}
