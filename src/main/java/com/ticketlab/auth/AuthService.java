package com.ticketlab.auth;

import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }
    
    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new TicketLabException(ErrorCode.EMAIL_ALREADY_USED);
        }

        User saved = userRepository.save(new User(request.email(), passwordEncoder.encode(request.password())));

        return new UserResponse(saved.getId(), saved.getEmail());
    }
}
