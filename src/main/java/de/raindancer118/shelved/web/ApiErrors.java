package de.raindancer118.shelved.web;

import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.scan.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(de.raindancer118.shelved.book.DuplicateBookException.class)
    ResponseEntity<Map<String, Object>> duplicate(de.raindancer118.shelved.book.DuplicateBookException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(),
                "existingId", e.existing().getId().toString(), "title", e.existing().getTitle()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        var field = e.getBindingResult().getFieldError();
        return error(HttpStatus.BAD_REQUEST, field == null ? "Ungültige Eingabe" : field.getField() + ": " + field.getDefaultMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Bild ist zu groß (max. 25 MB)");
    }

    @ExceptionHandler(RateLimiter.LimitExceeded.class)
    ResponseEntity<Map<String, String>> limited(RateLimiter.LimitExceeded e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(LibraryAi.AiException.class)
    ResponseEntity<Map<String, String>> ai(LibraryAi.AiException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
