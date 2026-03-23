package com.ieum.ai.credential.service;

public record CredentialValidationResult(
        boolean valid,
        String failureReason
) {
    public static CredentialValidationResult success() {
        return new CredentialValidationResult(true, null);
    }

    public static CredentialValidationResult failed(String reason) {
        return new CredentialValidationResult(false, reason);
    }
}
