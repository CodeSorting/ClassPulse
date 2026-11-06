package com.example.classpulse.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

/** Immutable navigation arguments. A back stack entry always keeps its own IDs. */
public final class ScreenContext {
    private static final String KEY = "classpulse.screen";
    public final String lessonId, questionId, quizId, topicId;

    private ScreenContext(String lessonId, String questionId, String quizId, String topicId) {
        this.lessonId = clean(lessonId);
        this.questionId = clean(questionId);
        this.quizId = clean(quizId);
        this.topicId = clean(topicId);
    }

    public static ScreenContext empty() {
        return new ScreenContext("", "", "", "");
    }

    public ScreenContext forLesson(String id) {
        return new ScreenContext(id, "", "", "");
    }

    public ScreenContext withQuestion(String id) {
        return new ScreenContext(lessonId, id, quizId, topicId);
    }

    public ScreenContext withQuiz(String id) {
        return new ScreenContext(lessonId, questionId, id, topicId);
    }

    public ScreenContext withTopic(String id) {
        return new ScreenContext(lessonId, questionId, quizId, id);
    }

    public void writeTo(Bundle arguments) {
        Bundle value = new Bundle();
        value.putString("lesson", lessonId);
        value.putString("question", questionId);
        value.putString("quiz", quizId);
        value.putString("topic", topicId);
        arguments.putBundle(KEY, value);
    }

    public static ScreenContext from(@Nullable Bundle arguments) {
        Bundle value = arguments == null ? null : arguments.getBundle(KEY);
        return value == null
                ? empty()
                : new ScreenContext(
                        value.getString("lesson"),
                        value.getString("question"),
                        value.getString("quiz"),
                        value.getString("topic"));
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
