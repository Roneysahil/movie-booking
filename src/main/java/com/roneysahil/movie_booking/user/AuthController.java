package com.roneysahil.movie_booking.user;

import com.roneysahil.movie_booking.user.UserDtos.RegisterRequest;
import com.roneysahil.movie_booking.user.UserDtos.UserDto;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and identity. There is no /login endpoint: authentication is HTTP Basic on
 * every request, so there would be nothing for it to issue.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto register(@Valid @RequestBody RegisterRequest request) {
        return users.register(request);
    }

    @GetMapping("/me")
    public UserDto me(Principal principal) {
        return users.currentUser(principal.getName());
    }
}
