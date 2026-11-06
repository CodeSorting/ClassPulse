package com.example.classpulse.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregate counts only: never includes personal marks or unsubmitted question drafts. */
public final class LessonAnalytics {
    public static final List<String> TYPES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            Models.UNDERSTANDING, Models.SPEED, Models.TERM, Models.CALCULATION));
    public final int participants,
            respondingStudents,
            reactionEvents,
            submittedQuestions,
            unansweredQuestions;
    public final List<TopicStats> topics;

    private LessonAnalytics(
            int participants,
            int respondingStudents,
            int reactionEvents,
            int submittedQuestions,
            int unansweredQuestions,
            List<TopicStats> topics) {
        this.participants = participants;
        this.respondingStudents = respondingStudents;
        this.reactionEvents = reactionEvents;
        this.submittedQuestions = submittedQuestions;
        this.unansweredQuestions = unansweredQuestions;
        this.topics = Collections.unmodifiableList(topics);
    }

    public static LessonAnalytics from(Models.Lesson lesson) {
        Set<String> members = new HashSet<>();
        for (Models.Membership member : lesson.members)
            if (!member.studentId.isEmpty()) members.add(member.studentId);
        Map<String, Accumulator> byTopic = new LinkedHashMap<>();
        for (Models.Topic topic : lesson.topics) byTopic.put(topic.id, new Accumulator(topic));
        Set<String> responders = new HashSet<>();
        int events = 0, questions = 0, unanswered = 0;
        for (Models.Reaction reaction : lesson.reactions) {
            Accumulator topic = byTopic.get(reaction.topicId);
            int type = TYPES.indexOf(reaction.type);
            if (topic == null || type < 0 || !members.contains(reaction.studentId)) continue;
            topic.events++;
            topic.responders.add(reaction.studentId);
            topic.byType.get(type).add(reaction.studentId);
            events++;
            responders.add(reaction.studentId);
        }
        for (Models.Question question : lesson.questions) {
            if (Models.DRAFT.equals(question.status)) continue;
            Accumulator topic = byTopic.get(question.topicId);
            if (topic == null) continue;
            topic.questions++;
            questions++;
            if (!question.published
                    || question.answer == null
                    || question.answer.trim().isEmpty()) {
                topic.unanswered++;
                unanswered++;
            }
        }
        List<TopicStats> topics = new ArrayList<>();
        for (Accumulator value : byTopic.values()) topics.add(new TopicStats(value));
        return new LessonAnalytics(
                members.size(), responders.size(), events, questions, unanswered, topics);
    }

    public static final class TopicStats {
        public final String topicId, title;
        public final int respondingStudents, reactionEvents, questions, unanswered;
        private final int[] byType;

        private TopicStats(Accumulator value) {
            topicId = value.topic.id;
            title = value.topic.title;
            respondingStudents = value.responders.size();
            reactionEvents = value.events;
            questions = value.questions;
            unanswered = value.unanswered;
            byType = new int[TYPES.size()];
            for (int i = 0; i < byType.length; i++) byType[i] = value.byType.get(i).size();
        }

        public int studentsFor(String type) {
            int index = TYPES.indexOf(type);
            return index < 0 ? 0 : byType[index];
        }
    }

    private static final class Accumulator {
        final Models.Topic topic;
        final Set<String> responders = new HashSet<>();
        final List<Set<String>> byType = new ArrayList<>();
        int events, questions, unanswered;

        Accumulator(Models.Topic topic) {
            this.topic = topic;
            for (int i = 0; i < TYPES.size(); i++) byType.add(new HashSet<>());
        }
    }
}
