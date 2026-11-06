package com.example.classpulse.online;

import android.net.Uri;

/** An online token has its own namespace; a demo session ID is never an admission token. */
public final class OnlineInvites {
    private OnlineInvites() {}

    public static String token(String value) {
        try {
            Uri u = Uri.parse(value.trim());
            if ("classpulse".equals(u.getScheme())
                    && "join".equals(u.getHost())
                    && u.getPathSegments().size() == 2
                    && "invite".equals(u.getPathSegments().get(0))) {
                String t = u.getLastPathSegment();
                return t != null && t.matches("[A-Za-z0-9_-]{40,100}") ? t : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
