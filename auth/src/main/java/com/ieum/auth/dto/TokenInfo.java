package com.ieum.auth.dto;

public record TokenInfo(String accessToken, String refreshToken, long expiresIn) {
}
