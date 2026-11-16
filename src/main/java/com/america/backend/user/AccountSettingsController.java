package com.america.backend.user;

import java.util.Map;
import com.america.backend.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class AccountSettingsController {
    public record GenderInput(@NotNull @Pattern(regexp = "boy|girl") String gender) {}
    private final AccountSettingsService service;
    public AccountSettingsController(AccountSettingsService service) { this.service = service; }
    @GetMapping("/settings")
    public AccountSettingsService.Snapshot get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(CurrentUser.id(jwt));
    }
    @PutMapping("/store-profile")
    public AccountSettingsService.Snapshot saveProfile(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountSettingsService.ProfileInput input) {
        return service.saveProfile(CurrentUser.id(jwt), input);
    }
    @PatchMapping("/staff-preferences/{assistant}")
    public Map<String, String> saveGender(@AuthenticationPrincipal Jwt jwt, @PathVariable String assistant,
                                         @Valid @RequestBody GenderInput input) {
        return service.saveGender(CurrentUser.id(jwt), assistant, input.gender());
    }
}
