package com.example.mfaapp.service;

/** The request is well-formed but internally inconsistent, e.g. a lesson from a different module. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
