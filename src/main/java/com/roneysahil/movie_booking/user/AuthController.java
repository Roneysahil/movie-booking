package com.roneysahil.movie_booking.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and identity. There is no /login: authentication is HTTP Basic on every
 * request, so a login endpoint would have nothing to issue.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
                    String password,
            @NotBlank String fullName) {}

    public record UserDto(Long id, String email, String fullName, String role) {}

    private final AtomicLong ids = new AtomicLong(1);

    /** New accounts are always CUSTOMER; admins are provisioned, never self-registered. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto register(@Valid @RequestBody RegisterRequest request) {
        return new UserDto(
                ids.incrementAndGet(), request.email(), request.fullName(), "CUSTOMER");
    }

    @GetMapping("/me")
    public UserDto me(Principal principal) {
        return new UserDto(1L, principal.getName(), principal.getName(), "CUSTOMER");
    }
}
