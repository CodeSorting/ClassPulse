package com.example.classpulse.data;

import androidx.annotation.NonNull;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Relation;
import java.util.List;

/** Local demo schema. These rows are not a Firestore wire format or authorization boundary. */
public final class LocalEntities {
    private LocalEntities() {}

    @Entity(
            tableName = "lessons",
            indices = {
                @Index(
                        value = {"code"},
                        unique = true)
            })
    public static class LessonRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String title = "", description = "", code = "", teacherId = "", status = "READY";
        public String currentTopicId, currentVisitId, resumeTopicId;
        public long createdAt;
        public boolean questionsOpen = true;
    }

    @Entity(
            tableName = "topics",
            foreignKeys =
                    @ForeignKey(
                            entity = LessonRow.class,
                            parentColumns = "id",
                            childColumns = "sessionId",
                            onDelete = ForeignKey.CASCADE),
            indices = {@Index("sessionId")})
    public static class TopicRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String sessionId = "", title = "";
        public String parentTopicId;
        public int position;
        public boolean completed;
    }

    @Entity(
            tableName = "topic_visits",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TopicRow.class,
                        parentColumns = "id",
                        childColumns = "topicId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {@Index("sessionId"), @Index("topicId")})
    public static class VisitRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String sessionId = "", topicId = "";
        public long startedAt, endedAt;
        public boolean revisit;
    }

    @Entity(
            tableName = "memberships",
            primaryKeys = {"sessionId", "studentId"},
            foreignKeys =
                    @ForeignKey(
                            entity = LessonRow.class,
                            parentColumns = "id",
                            childColumns = "sessionId",
                            onDelete = ForeignKey.CASCADE),
            indices = {@Index("sessionId")})
    public static class MemberRow {
        @NonNull public String sessionId = "", studentId = "";
        public long joinedAt;
    }

    @Entity(
            tableName = "questions",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TopicRow.class,
                        parentColumns = "id",
                        childColumns = "topicId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {@Index("sessionId"), @Index("topicId"), @Index("studentId")})
    public static class QuestionRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String sessionId = "",
                topicId = "",
                studentId = "",
                text = "",
                answer = "",
                answerDraft = "",
                status = "DRAFT";
        public boolean published;
        public int answerVersion;
        public long createdAt, updatedAt, submittedAt, answeredAt;
    }

    @Entity(
            tableName = "reactions",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TopicRow.class,
                        parentColumns = "id",
                        childColumns = "topicId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = VisitRow.class,
                        parentColumns = "id",
                        childColumns = "visitId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {
                @Index("sessionId"),
                @Index("topicId"),
                @Index("visitId"),
                @Index(
                        value = {"studentId", "visitId", "type"},
                        unique = true)
            })
    public static class ReactionRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String sessionId = "", topicId = "", visitId = "", studentId = "", type = "";
        public long createdAt;
    }

    @Entity(
            tableName = "marks",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TopicRow.class,
                        parentColumns = "id",
                        childColumns = "topicId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {@Index("sessionId"), @Index("topicId"), @Index("studentId")})
    public static class MarkRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String sessionId = "", topicId = "", studentId = "", keyword = "";
        public long createdAt;
    }

    @Entity(
            tableName = "materials",
            foreignKeys =
                    @ForeignKey(
                            entity = LessonRow.class,
                            parentColumns = "id",
                            childColumns = "sessionId",
                            onDelete = ForeignKey.CASCADE))
    public static class MaterialRow {
        @PrimaryKey @NonNull public String sessionId = "";
        @NonNull
        public String original = "",
                summary = "",
                supplement = "",
                draftOriginal = "",
                draftSummary = "",
                draftSupplement = "";
        public boolean published, hasDraft;
        public int version;
        public long updatedAt;
    }

    @Entity(
            tableName = "downloads",
            foreignKeys =
                    @ForeignKey(
                            entity = LessonRow.class,
                            parentColumns = "id",
                            childColumns = "sessionId",
                            onDelete = ForeignKey.CASCADE),
            indices = {
                @Index("sessionId"),
                @Index(
                        value = {"studentId", "sessionId"},
                        unique = true)
            })
    public static class DownloadRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String sessionId = "", studentId = "", original = "", summary = "", supplement = "";
        public int version;
        public long downloadedAt;
    }

    @Entity(
            tableName = "quizzes",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TopicRow.class,
                        parentColumns = "id",
                        childColumns = "topicId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {@Index("sessionId"), @Index("topicId")})
    public static class QuizRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String sessionId = "",
                topicId = "",
                title = "",
                question = "",
                optionsJson = "[]",
                explanation = "";
        @NonNull
        public String draftTitle = "",
                draftQuestion = "",
                draftOptionsJson = "[]",
                draftExplanation = "";
        public int correctIndex, draftCorrectIndex, version;
        public boolean published, hasDraft;
        public long createdAt;
    }

    @Entity(
            tableName = "attempts",
            foreignKeys = {
                @ForeignKey(
                        entity = LessonRow.class,
                        parentColumns = "id",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = QuizRow.class,
                        parentColumns = "id",
                        childColumns = "quizId",
                        onDelete = ForeignKey.CASCADE)
            },
            indices = {
                @Index("sessionId"),
                @Index("quizId"),
                @Index(
                        value = {"studentId", "quizId"},
                        unique = true)
            })
    public static class AttemptRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull
        public String sessionId = "",
                quizId = "",
                studentId = "",
                question = "",
                optionsJson = "[]",
                explanation = "";
        public int choice, quizVersion, correctIndex;
        public boolean correct;
        public long createdAt;
    }

    public static class LessonGraph {
        @Embedded public LessonRow lesson;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<TopicRow> topics;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<VisitRow> visits;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<MemberRow> members;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<QuestionRow> questions;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<ReactionRow> reactions;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<MarkRow> marks;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<MaterialRow> materials;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<DownloadRow> downloads;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<QuizRow> quizzes;

        @Relation(parentColumn = "id", entityColumn = "sessionId")
        public List<AttemptRow> attempts;
    }
}
