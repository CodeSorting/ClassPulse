package com.example.classpulse.domain;

import java.util.ArrayList;
import java.util.List;

/** Read-only UI snapshots. All changes go through LessonRepository. */
public final class Models {
    public static final String TEACHER_ID = "demo-teacher";
    public static final String STUDENT_ID = "demo-student";
    public static final String READY = "READY", LIVE = "LIVE", ENDED = "ENDED";
    public static final String DRAFT = "DRAFT", SUBMITTED = "SUBMITTED", ANSWERED = "ANSWERED";
    public static final String UNDERSTANDING = "UNDERSTANDING",
            SPEED = "SPEED",
            TERM = "TERM",
            CALCULATION = "CALCULATION";

    private Models() {}

    public static final class Lesson {
        public String id = "",
                title = "",
                description = "",
                code = "",
                teacherId = "",
                status = READY;
        public String currentTopicId, currentVisitId, resumeTopicId;
        public long createdAt;
        public boolean questionsOpen = true;
        public List<Topic> topics = new ArrayList<>();
        public List<Question> questions = new ArrayList<>();
        public List<Reaction> reactions = new ArrayList<>();
        public List<Mark> marks = new ArrayList<>();
        public List<Quiz> quizzes = new ArrayList<>();
        public List<Attempt> attempts = new ArrayList<>();
        public List<Membership> members = new ArrayList<>();
        public List<Download> downloads = new ArrayList<>();
        public List<Visit> visits = new ArrayList<>();
        public Material material;

        public Topic currentTopic() {
            return topic(currentTopicId);
        }

        public Topic topic(String id) {
            if (id != null) for (Topic t : topics) if (id.equals(t.id)) return t;
            return null;
        }

        public Question question(String id) {
            if (id != null) for (Question q : questions) if (id.equals(q.id)) return q;
            return null;
        }

        public Quiz quiz(String id) {
            if (id != null) for (Quiz q : quizzes) if (id.equals(q.id)) return q;
            return null;
        }

        public boolean isMember(String uid) {
            if (uid != null) for (Membership m : members) if (uid.equals(m.studentId)) return true;
            return false;
        }
    }

    public static final class Topic {
        public String id = "", sessionId = "", parentTopicId, title = "";
        public int position;
        public boolean completed;
    }

    public static final class Visit {
        public String id = "", sessionId = "", topicId = "";
        public long startedAt, endedAt;
        public boolean revisit;
    }

    public static final class Question {
        public String id = "",
                sessionId = "",
                topicId = "",
                studentId = "",
                text = "",
                answer = "",
                answerDraft = "",
                status = DRAFT;
        public boolean published;
        public int answerVersion;
        public long createdAt, updatedAt, submittedAt, answeredAt;
    }

    public static final class Reaction {
        public String id = "",
                sessionId = "",
                topicId = "",
                visitId = "",
                studentId = "",
                type = "";
        public long createdAt;
    }

    public static final class Mark {
        public String id = "", sessionId = "", topicId = "", studentId = "", keyword = "";
        public long createdAt;
    }

    public static final class Quiz {
        public String id = "",
                sessionId = "",
                topicId = "",
                title = "",
                question = "",
                explanation = "";
        public List<String> options = new ArrayList<>();
        public int correctIndex, version = 1;
        public boolean published, hasDraft;
        public String draftTitle = "", draftQuestion = "", draftExplanation = "";
        public List<String> draftOptions = new ArrayList<>();
        public int draftCorrectIndex;
        public long createdAt;
    }

    public static final class Attempt {
        public String id = "", sessionId = "", quizId = "", studentId = "";
        public int choice, quizVersion, correctIndex;
        public boolean correct;
        public long createdAt;
        public String question = "", explanation = "";
        public List<String> options = new ArrayList<>();
    }

    public static final class Membership {
        public String sessionId = "", studentId = "";
        public long joinedAt;
    }

    public static final class Material {
        public String sessionId = "", original = "", summary = "", supplement = "";
        public String draftOriginal = "", draftSummary = "", draftSupplement = "";
        public boolean published, hasDraft;
        public int version;
        public long updatedAt;
    }

    public static final class Download {
        public String id = "",
                sessionId = "",
                studentId = "",
                original = "",
                summary = "",
                supplement = "";
        public int version;
        public long downloadedAt;
    }
}
