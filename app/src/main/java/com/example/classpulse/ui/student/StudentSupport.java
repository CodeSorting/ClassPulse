package com.example.classpulse.ui.student;

import com.example.classpulse.domain.Models;

final class StudentSupport {
    private StudentSupport() {}

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static boolean draft(Models.Question question) {
        return "DRAFT".equals(question.status);
    }

    static boolean member(Models.Lesson lesson, String userId) {
        return lesson != null && lesson.isMember(userId);
    }

    static Models.Attempt attempt(Models.Lesson lesson, String quizId, String userId) {
        Models.Attempt latest = null;
        if (lesson.attempts != null)
            for (Models.Attempt item : lesson.attempts) {
                if (safe(quizId).equals(item.quizId)
                        && safe(userId).equals(item.studentId)
                        && (latest == null || item.createdAt > latest.createdAt)) latest = item;
            }
        return latest;
    }

    static Models.Download download(Models.Lesson lesson, String userId) {
        Models.Download latest = null;
        if (lesson.downloads != null)
            for (Models.Download item : lesson.downloads) {
                if (safe(userId).equals(item.studentId)
                        && (latest == null || item.downloadedAt > latest.downloadedAt))
                    latest = item;
            }
        return latest;
    }

    static String reaction(String type) {
        if ("UNDERSTANDING".equals(type)) return "이해가 안 돼요";
        if ("SPEED".equals(type)) return "너무 빨라요";
        if ("TERM".equals(type)) return "용어가 어려워요";
        if ("CALCULATION".equals(type)) return "계산을 놓쳤어요";
        return "수업 반응";
    }
}
