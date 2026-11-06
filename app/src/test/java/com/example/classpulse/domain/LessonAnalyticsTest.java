package com.example.classpulse.domain;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LessonAnalyticsTest {
    private Models.Lesson lesson() {
        Models.Lesson lesson = new Models.Lesson();
        for (String id : Arrays.asList("A", "B")) {
            Models.Topic t = new Models.Topic();
            t.id = id;
            t.title = id;
            lesson.topics.add(t);
        }
        for (String id : Arrays.asList("student1", "student2")) {
            Models.Membership m = new Models.Membership();
            m.studentId = id;
            lesson.members.add(m);
        }
        return lesson;
    }

    private void reaction(Models.Lesson lesson, String student, String topic, String type) {
        Models.Reaction r = new Models.Reaction();
        r.studentId = student;
        r.topicId = topic;
        r.type = type;
        lesson.reactions.add(r);
    }

    private void question(
            Models.Lesson lesson, String topic, String status, boolean published, String answer) {
        Models.Question q = new Models.Question();
        q.topicId = topic;
        q.status = status;
        q.published = published;
        q.answer = answer;
        lesson.questions.add(q);
    }

    @Test
    public void revisitEventsDoNotInflateStudentCounts() {
        Models.Lesson lesson = lesson();
        reaction(lesson, "student1", "A", Models.SPEED);
        reaction(lesson, "student1", "A", Models.SPEED);
        reaction(lesson, "student1", "A", Models.TERM);
        reaction(lesson, "student2", "A", Models.SPEED);
        reaction(lesson, "student1", "B", Models.CALCULATION);
        LessonAnalytics actual = LessonAnalytics.from(lesson);
        assertEquals(2, actual.participants);
        assertEquals(2, actual.respondingStudents);
        assertEquals(5, actual.reactionEvents);
        assertEquals(4, actual.topics.get(0).reactionEvents);
        assertEquals(2, actual.topics.get(0).respondingStudents);
        assertEquals(2, actual.topics.get(0).studentsFor(Models.SPEED));
        assertEquals(1, actual.topics.get(0).studentsFor(Models.TERM));
        assertEquals(1, actual.topics.get(1).studentsFor(Models.CALCULATION));
    }

    @Test
    public void draftsAndPrivateMarksDoNotBecomeTeacherQuestions() {
        Models.Lesson lesson = lesson();
        question(lesson, "A", Models.DRAFT, false, "");
        question(lesson, "A", Models.SUBMITTED, false, "");
        question(lesson, "A", Models.ANSWERED, true, "공개 답변");
        question(lesson, "B", Models.ANSWERED, true, "  ");
        lesson.marks.add(new Models.Mark());
        LessonAnalytics actual = LessonAnalytics.from(lesson);
        assertEquals(3, actual.submittedQuestions);
        assertEquals(2, actual.unansweredQuestions);
        assertEquals(2, actual.topics.get(0).questions);
        assertEquals(1, actual.topics.get(0).unanswered);
        assertEquals(0, actual.respondingStudents);
        assertEquals(0, actual.reactionEvents);
    }

    @Test
    public void unknownTypesTopicsAndNonmemberReactionsAreExcluded() {
        Models.Lesson lesson = lesson();
        reaction(lesson, "outsider", "A", Models.SPEED);
        reaction(lesson, "student1", "missing", Models.SPEED);
        reaction(lesson, "student1", "A", "UNKNOWN");
        question(lesson, "missing", Models.SUBMITTED, false, "");
        Models.Membership duplicate = new Models.Membership();
        duplicate.studentId = "student1";
        lesson.members.add(duplicate);
        LessonAnalytics actual = LessonAnalytics.from(lesson);
        assertEquals(2, actual.participants);
        assertEquals(0, actual.respondingStudents);
        assertEquals(0, actual.submittedQuestions);
        assertEquals("A", actual.topics.get(0).topicId);
        assertEquals("B", actual.topics.get(1).topicId);
    }
}
