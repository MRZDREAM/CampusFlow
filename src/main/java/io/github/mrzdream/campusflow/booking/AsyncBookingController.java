package io.github.mrzdream.campusflow.booking;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reservation-requests")
@ConditionalOnProperty(name = "campus.delivery.enabled", havingValue = "true")
class AsyncBookingController {
    private final AsyncBookingService requests;

    AsyncBookingController(AsyncBookingService requests) {
        this.requests = requests;
    }

    @PostMapping
    ResponseEntity<AsyncBookingService.View> submit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody BookingController.SlotSelection input) {
        var body = requests.submit(BookingController.userId(jwt), input.slotId(), key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/reservation-requests/" + key)).body(body);
    }

    @GetMapping("/{id}")
    AsyncBookingService.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return requests.get(BookingController.userId(jwt), id);
    }
}
