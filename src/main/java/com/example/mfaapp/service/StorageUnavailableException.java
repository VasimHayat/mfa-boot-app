package com.example.mfaapp.service;

/**
 * The object store could not be reached or refused the operation.
 *
 * <p>Distinct from a bad request: the caller did nothing wrong, so this surfaces as 503 and the SPA
 * offers a retry rather than telling the user their file was invalid.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageUnavailableException(String message) {
        super(message);
    }
}
