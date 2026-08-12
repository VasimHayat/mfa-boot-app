package com.example.mfaapp.web;

import com.example.mfaapp.service.InvalidRequestException;
import com.example.mfaapp.service.ResourceNotFoundException;
import com.example.mfaapp.service.StorageUnavailableException;
import com.example.mfaapp.web.dto.AuthDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps service and binding failures onto the JSON error envelope the SPA expects. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "The requested resource does not exist"));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(InvalidRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", ex.getMessage()));
    }

    /** A bad enum value in a filter parameter, e.g. {@code ?difficulty=EXPERT}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_parameter",
                        "Unsupported value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_request", "Request body failed validation"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_request", "Request body could not be parsed"));
    }

    /**
     * The object store is down or lost an object. The caller did nothing wrong, so this must not
     * read as a validation failure — the SPA offers a retry on 503.
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> storageUnavailable(StorageUnavailableException ex) {
        log.error("Document storage operation failed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("storage_unavailable",
                        "File storage is temporarily unavailable. Please try again."));
    }

    /**
     * Tripped by the servlet container before the controller runs, so the size limit has to be
     * reported here rather than in {@code DocumentService}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("file_too_large", "That file is larger than the upload limit"));
    }

    /**
     * Reached when MFA setup is requested for an account that is already enrolled — the state, not
     * the request, is wrong.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> illegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", ex.getMessage()));
    }
}
