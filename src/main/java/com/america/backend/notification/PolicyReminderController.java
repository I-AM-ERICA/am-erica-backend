package com.america.backend.notification;

import com.america.backend.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/policy-reminder")
public class PolicyReminderController {
    public record SendNow(@NotNull @Positive Long storeId) {}
    public record Update(@NotNull Boolean enabled, @Positive Long storeId) {}
    private final PolicyReminderService service;
    public PolicyReminderController(PolicyReminderService service) { this.service = service; }
    @GetMapping
    public PolicyReminderService.Settings get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(CurrentUser.id(jwt));
    }
    @PostMapping("/send-now")
    public PolicyReminderService.DeliveryResult sendNow(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendNow request) {
        return service.sendNow(CurrentUser.id(jwt), request.storeId());
    }
    @PutMapping
    public PolicyReminderService.Settings update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Update request) {
        return service.update(CurrentUser.id(jwt), request.enabled(), request.storeId());
    }
}
