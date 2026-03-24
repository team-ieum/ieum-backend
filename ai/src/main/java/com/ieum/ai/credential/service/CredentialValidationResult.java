package com.ieum.ai.credential.service;

public record CredentialValidationResult(
        boolean valid,
        String failureReason,
        String provider
) {
    public static CredentialValidationResult success(String provider) {
        return new CredentialValidationResult(true, null, provider);
    }

    public static CredentialValidationResult failed(String provider, String reason) {
        return new CredentialValidationResult(false, reason, provider);
    }
}
