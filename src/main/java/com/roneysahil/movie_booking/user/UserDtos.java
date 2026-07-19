package com.roneysahil.movie_booking.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {}

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
                    String password,
            @NotBlank String fullName) {}

    /** Never exposes the password hash. */
    public record UserDto(Long id, String email, String fullName, String role, boolean active) {}
}
