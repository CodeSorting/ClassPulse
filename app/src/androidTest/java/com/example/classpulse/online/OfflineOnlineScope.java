package com.example.classpulse.online;

import android.content.Context;
import androidx.room.Room;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Auth/navigation tests get account-owned memory DBs and never contact production Firebase. */
public final class OfflineOnlineScope implements AutoCloseable {
    private final OnlineServices.Factory previous;
    private final List<OnlineRepository> repositories = new ArrayList<>();

    public OfflineOnlineScope(Context context, Supplier<String> currentUid) {
        previous = OnlineServices.testFactory;
        OnlineServices.close();
        OnlineServices.testFactory =
                (c, uid) -> {
                    OnlineDatabase db =
                            Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build();
                    OnlineTransport transport =
                            new OnlineTransport() {
                                public boolean authenticatedAs(String user) {
                                    return user.equals(currentUid.get());
                                }

                                public CompletableFuture<JSONObject> call(
                                        String command, JSONObject payload) {
                                    return unavailable();
                                }

                                public CompletableFuture<JSONObject> receipt(
                                        String user, String operation) {
                                    return CompletableFuture.completedFuture(new JSONObject());
                                }

                                public CompletableFuture<JSONObject> page(
                                        String user,
                                        String sid,
                                        String kind,
                                        boolean owner,
                                        String cursor) {
                                    return unavailable();
                                }

                                public Subscription watchHome(
                                        String user,
                                        java.util.function.Consumer<String> ids,
                                        Sink sink) {
                                    return () -> {};
                                }

                                public Subscription watchLesson(
                                        String user, String sid, boolean owner, Sink sink) {
                                    return () -> {};
                                }

                                private CompletableFuture<JSONObject> unavailable() {
                                    CompletableFuture<JSONObject> result =
                                            new CompletableFuture<>();
                                    result.completeExceptionally(
                                            new Failure("TEST_OFFLINE", "테스트 계정에는 수업이 없어요."));
                                    return result;
                                }
                            };
                    OnlineRepository repo = new OnlineRepository(c, uid, db, transport, false);
                    repositories.add(repo);
                    return repo;
                };
    }

    @Override
    public void close() {
        OnlineServices.close();
        for (OnlineRepository repo : repositories) repo.close();
        OnlineServices.testFactory = previous;
    }
}
