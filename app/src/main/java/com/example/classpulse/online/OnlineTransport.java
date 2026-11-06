package com.example.classpulse.online;

import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;

public interface OnlineTransport {
    interface Subscription {
        void close();
    }

    interface Sink {
        void document(String kind, String id, JSONObject value);

        void error(String reason, String message);
    }

    CompletableFuture<JSONObject> call(String command, JSONObject payload);

    CompletableFuture<JSONObject> receipt(String uid, String operationId);

    CompletableFuture<JSONObject> page(
            String uid, String sid, String kind, boolean owner, String lastId);

    Subscription watchHome(String uid, java.util.function.Consumer<String> sessionId, Sink errors);

    Subscription watchLesson(String uid, String sid, boolean owner, Sink sink);

    boolean authenticatedAs(String uid);

    final class Failure extends RuntimeException {
        public final String reason;

        public Failure(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Failure(String reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }
}
