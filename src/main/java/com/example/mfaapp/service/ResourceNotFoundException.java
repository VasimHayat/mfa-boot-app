package com.example.mfaapp.service;

/**
 * The resource does not exist, or exists but is not visible to the caller. Both cases map to 404 so
 * the catalog cannot be probed for the existence of unpublished or role-gated modules.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
