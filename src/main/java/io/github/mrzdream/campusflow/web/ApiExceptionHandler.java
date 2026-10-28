package io.github.mrzdream.campusflow.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.mrzdream.campusflow.resource.application.ResourceNotFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFound(ResourceNotFoundException exception, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage());
        return handleExceptionInternal(exception, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "VALIDATION_FAILED", "Request fields are invalid.");
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.add(new FieldViolation(error.getField(), error.getDefaultMessage()));
        }
        errors.sort(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message));
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "VALIDATION_FAILED", "Request parameters are invalid.");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "INVALID_REQUEST", "Request body must be a valid JSON object.");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "INVALID_REQUEST", "Request parameter has an invalid type.");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private record FieldViolation(String field, String message) {
    }
}
