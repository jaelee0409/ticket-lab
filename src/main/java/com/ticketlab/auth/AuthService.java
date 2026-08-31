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
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }
    
    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new TicketLabException(ErrorCode.EMAIL_ALREADY_USED);
        }

        User saved = userRepository.save(new User(request.email(), passwordEncoder.encode(request.password())));

        return new UserResponse(saved.getId(), saved.getEmail());
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new TicketLabException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new TicketLabException(ErrorCode.INVALID_CREDENTIALS);
        }

        return new TokenResponse(tokenProvider.createAccessToken(user.getId(), user.getEmail()), tokenProvider.getAccessTokenTtl().toSeconds());
    }

    @Transactional(readOnly = true)
    public UserResponse findMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(user.getId(), user.getEmail());
    }
}
