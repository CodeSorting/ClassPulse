package com.example.classpulse.auth;

/** Public profile only. ID/access/refresh tokens are never kept in UI state. */
public final class AuthIdentity {
    public final String uid, name, email;

    public AuthIdentity(String uid, String name, String email) {
        if (uid == null || uid.isEmpty())
            throw new IllegalArgumentException("An authenticated UID is required");
        this.uid = uid;
        this.name = name == null ? "" : name;
        this.email = email == null ? "" : email;
    }
}
