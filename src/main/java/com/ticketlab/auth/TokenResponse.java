package com.ticketlab.auth;

public record TokenResponse(String accessToken, long expiresInSeconds) {
    
}
