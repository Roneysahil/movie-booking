package com.roneysahil.movie_booking.user;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.user.UserDtos.RegisterRequest;
import com.roneysahil.movie_booking.user.UserDtos.UserDto;
import com.roneysahil.movie_booking.user.domain.User;
import com.roneysahil.movie_booking.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * Self-registration always produces a CUSTOMER. Admin accounts are provisioned
     * directly — allowing a request body to choose its own role would be a privilege
     * escalation hole.
     */
    @Transactional
    public UserDto register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        // Fast path only. This is check-then-act: two simultaneous registrations for the
        // same address can both pass here, because at READ COMMITTED neither sees the
        // other's uncommitted insert. The unique constraint below is what actually
        // guarantees uniqueness.
        if (users.existsByEmail(email)) {
            throw new BusinessRuleException("An account already exists for " + email);
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setRole(User.Role.CUSTOMER);
        user.setActive(true);

        try {
            // Flushed here rather than at commit, so the constraint violation surfaces
            // inside this try instead of escaping the method as a 500.
            return toDto(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            // Lost the race. The caller gets the same 422 as the sequential case, so the
            // outcome does not depend on timing.
            throw new BusinessRuleException("An account already exists for " + email);
        }
    }

    /** Reports the authenticated principal's real identity and role. */
    @Transactional(readOnly = true)
    public UserDto currentUser(String email) {
        return users.findByEmail(email)
                .map(this::toDto)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.isActive());
    }
}
