package pleos.nextkey.common;

import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> domain(ApiError ex) { return error(ex.status(), ex.code()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return error(413, "FILE_TOO_LARGE"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() { return error(409, "DATA_CONFLICT"); }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<?> contention() { return error(409, "RETRY_TRANSACTION"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception ex) {
        if (ex instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            return error(status, switch (status) {
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "INVALID_REQUEST";
            });
        }
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || ex instanceof org.springframework.beans.TypeMismatchException) {
            return error(400, "INVALID_REQUEST");
        }
        // Exception messages can include SQL bind values, coordinates or raw request bodies.
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Unhandled API failure of type {}", ex.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR");
    }

    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
