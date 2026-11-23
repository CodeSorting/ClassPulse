package com.example.classpulse.online;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.classpulse.online.LectureRecordingPlan.Action.*;

public class LectureRecordingPlanTest {
    @Test public void childStepsKeepOneFileAndMajorTransitionsRotateExactlyOnce() {
        LectureRecordingPlan p = new LectureRecordingPlan();
        assertEquals(START, p.update(1, true, true, "major1"));
        assertEquals(NONE, p.update(2, true, true, "major1"));
        assertEquals(ROTATE, p.update(3, true, true, "major2"));
        assertEquals(NONE, p.update(3, true, true, "major2"));
        assertEquals(NONE, p.update(2, true, true, "major1"));
        assertEquals(ROTATE, p.update(4, true, true, "major1"));
    }
    @Test public void completingLastUnitReleasesMicAndRevisitCanResumeUntilEnd() {
        LectureRecordingPlan p = new LectureRecordingPlan();
        assertEquals(START, p.update(1, true, true, "major1"));
        assertEquals(WAIT, p.update(2, true, false, "major1"));
        assertEquals(NONE, p.update(2, true, false, "major1"));
        assertEquals(START, p.update(3, true, true, "major1"));
        assertEquals(STOP, p.update(4, false, false, "major1"));
        assertEquals(NONE, p.update(5, true, true, "major2"));
    }
    @Test public void localEndWinsAgainstDelayedLiveAcknowledgement() {
        LectureRecordingPlan p = new LectureRecordingPlan();
        p.update(1, true, true, "major1");
        p.close();
        assertEquals(NONE, p.update(2, true, true, "major2"));
    }
}
