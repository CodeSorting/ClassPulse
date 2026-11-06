package com.example.classpulse.online;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Database(
        entities = {
            OnlineDatabase.Session.class,
            OnlineDatabase.Document.class,
            OnlineDatabase.Draft.class,
            OnlineDatabase.Outbox.class,
            OnlineDatabase.Conflict.class
        },
        version = 1,
        exportSchema = true)
public abstract class OnlineDatabase extends RoomDatabase {
    public abstract Store store();

    public static String accountKey(String uid) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static OnlineDatabase open(Context c, String uid) {
        return Room.databaseBuilder(
                        c.getApplicationContext(),
                        OnlineDatabase.class,
                        "classpulse-user-" + accountKey(uid) + ".db")
                .build();
    }

    @Entity(tableName = "session_cache")
    public static class Session {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String ownerUid = "", title = "", status = "READY", json = "{}";
        public long revision, createdAt, verifiedAt;
        public boolean accessible = true;
    }

    @Entity(
            tableName = "document_cache",
            primaryKeys = {"sessionId", "kind", "id"})
    public static class Document {
        @NonNull public String sessionId = "", kind = "", id = "", json = "{}";
        public long revision, updatedAt, verifiedAt;
        public boolean deleted;
    }

    @Entity(
            tableName = "personal_drafts",
            primaryKeys = {"sessionId", "kind", "id"})
    public static class Draft {
        @NonNull
        public String sessionId = "",
                kind = "",
                id = "",
                topicId = "",
                text = "",
                state = "LOCAL_DRAFT";
        public long serverRevision, localRevision, baseAnswerVersion;
        public boolean dirty, closed;
    }

    @Entity(
            tableName = "outbox",
            indices = {@Index(value = {"entityKey", "createdAt"}), @Index("state")})
    public static class Outbox {
        @PrimaryKey @NonNull public String operationId = "";
        @NonNull
        public String ownerUid = "",
                sessionId = "",
                entityKey = "",
                command = "",
                payload = "{}",
                state = "PENDING",
                errorCode = "",
                errorMessage = "";
        public long createdAt, leaseUntil, localRevisionAtEnqueue;
        public int attempts;
        public boolean foreground;
    }

    @Entity(
            tableName = "edit_conflicts",
            primaryKeys = {"sessionId", "kind", "id"})
    public static class Conflict {
        @NonNull
        public String sessionId = "", kind = "", id = "", localText = "", remoteJson = "{}";
        public long remoteRevision;
    }

    @Dao
    public interface Store {
        @Query("SELECT * FROM session_cache WHERE accessible=1 ORDER BY createdAt DESC")
        LiveData<List<Session>> sessions();

        @Query("SELECT * FROM session_cache WHERE id=:id")
        Session session(String id);

        @Upsert
        void session(Session row);

        @Query("SELECT * FROM document_cache WHERE sessionId=:sid ORDER BY updatedAt DESC, id")
        LiveData<List<Document>> documents(String sid);

        @Query("SELECT * FROM document_cache WHERE sessionId=:sid AND kind=:kind AND id=:id")
        Document document(String sid, String kind, String id);

        @Upsert
        void document(Document row);

        @Query("SELECT * FROM personal_drafts WHERE sessionId=:sid ORDER BY localRevision DESC")
        LiveData<List<Draft>> drafts(String sid);

        @Query("SELECT * FROM personal_drafts WHERE sessionId=:sid AND kind=:kind AND id=:id")
        Draft draft(String sid, String kind, String id);

        @Upsert
        void draft(Draft row);

        @Query("SELECT * FROM outbox ORDER BY createdAt DESC")
        LiveData<List<Outbox>> operations();

        @Query("SELECT * FROM outbox WHERE operationId=:id")
        Outbox operation(String id);

        @Upsert
        void operation(Outbox row);

        @Query("SELECT COALESCE(MAX(createdAt),0) FROM outbox")
        long newestOperationTime();

        @Query("SELECT COUNT(*) FROM outbox WHERE state='SENDING'")
        int inFlightCount();

        @Query(
                "SELECT * FROM outbox WHERE entityKey=:key AND state NOT IN ('ACKED','REJECTED') ORDER BY createdAt DESC LIMIT 1")
        Outbox lastForEntity(String key);

        @Query(
                "SELECT * FROM outbox WHERE state IN ('PENDING','RETRY_WAIT') AND (:foreground OR foreground=0) AND NOT EXISTS (SELECT 1 FROM outbox prev WHERE prev.entityKey=outbox.entityKey AND prev.createdAt < outbox.createdAt AND prev.state NOT IN ('ACKED','REJECTED')) ORDER BY createdAt LIMIT 1")
        Outbox next(boolean foreground);

        @Query(
                "UPDATE outbox SET state='SENDING', leaseUntil=:until, attempts=attempts+1 WHERE operationId=:id AND state IN ('PENDING','RETRY_WAIT')")
        int claim(String id, long until);

        @Query(
                "SELECT * FROM outbox WHERE (state='SENDING' AND leaseUntil<:now) OR state='NEEDS_CONFIRMATION'")
        List<Outbox> expired(long now);

        @Query(
                "SELECT * FROM outbox WHERE entityKey=:key AND state IN ('PENDING','RETRY_WAIT','CONFLICT')")
        List<Outbox> pendingEntity(String key);

        @Upsert
        void conflict(Conflict row);

        @Query("SELECT * FROM edit_conflicts WHERE sessionId=:sid")
        LiveData<List<Conflict>> conflicts(String sid);

        @Query("SELECT * FROM edit_conflicts WHERE sessionId=:sid AND kind=:kind AND id=:id")
        Conflict conflict(String sid, String kind, String id);

        @Query("DELETE FROM edit_conflicts WHERE sessionId=:sid AND kind=:kind AND id=:id")
        void clearConflict(String sid, String kind, String id);
    }
}
