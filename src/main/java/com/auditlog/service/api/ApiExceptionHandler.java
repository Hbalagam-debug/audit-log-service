package com.auditlog.service.api;

import com.auditlog.service.api.dto.ErrorResponse;
import com.auditlog.service.service.InvalidCursorException;
import com.auditlog.service.service.PayloadDecryptionException;
import com.auditlog.service.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
        MethodArgumentNotValidException ex,
        WebRequest request
    ) {
        List<String> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.add(error.getField() + ": " + error.getDefaultMessage())
        );

        ErrorResponse response = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "VALIDATION_ERROR",
            "Invalid request payload",
            request.getDescription(false).replace("uri=", ""),
            errors
        );

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
        IllegalArgumentException ex,
        WebRequest request
    ) {
        int status = ex.getMessage() != null && ex.getMessage().contains("exceeds maximum size")
            ? HttpStatus.PAYLOAD_TOO_LARGE.value()
            : HttpStatus.BAD_REQUEST.value();

        ErrorResponse response = new ErrorResponse(
            status,
            "BAD_REQUEST",
            ex.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(
            response,
            status == HttpStatus.PAYLOAD_TOO_LARGE.value() 
                ? HttpStatus.PAYLOAD_TOO_LARGE 
                : HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursorException(
        InvalidCursorException ex,
        WebRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "INVALID_CURSOR",
            ex.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
        ResourceNotFoundException ex,
        WebRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            "NOT_FOUND",
            ex.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(PayloadDecryptionException.class)
    public ResponseEntity<ErrorResponse> handlePayloadDecryptionException(WebRequest request) {
        ErrorResponse response = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "ENCRYPTED_PAYLOAD_UNAVAILABLE",
            "Encrypted payload is unavailable for one or more events",
            request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(
        RuntimeException ex,
        WebRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "INTERNAL_SERVER_ERROR",
            "An unexpected error occurred",
            request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
