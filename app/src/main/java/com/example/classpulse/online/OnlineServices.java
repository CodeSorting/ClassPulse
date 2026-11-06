package com.example.classpulse.online;

import android.content.Context;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;

/** One active account scope. Replacing an identity invalidates listeners, workers and callbacks. */
public final class OnlineServices {
    interface Factory {
        OnlineRepository create(Context context, String uid);
    }

    static Factory testFactory;
    private static OnlineRepository current;

    private OnlineServices() {}

    public static synchronized OnlineRepository open(Context context, String uid) {
        if (current != null && current.uid.equals(uid)) return current;
        close();
        current =
                testFactory != null
                        ? testFactory.create(context, uid)
                        : new OnlineRepository(
                                context,
                                uid,
                                OnlineDatabase.open(context, uid),
                                new FirebaseOnlineTransport(FirebaseApp.getInstance()),
                                true);
        current.retry();
        return current;
    }

    public static synchronized void identityChanged(String uid) {
        if (current != null && !current.uid.equals(uid)) close();
    }

    public static synchronized void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    static synchronized OnlineRepository worker(Context context, String uid) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || !uid.equals(FirebaseAuth.getInstance().getCurrentUser().getUid())) return null;
        return open(context, uid);
    }
}
