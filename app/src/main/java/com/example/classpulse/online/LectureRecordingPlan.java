package com.example.classpulse.online;

/** Monotonic progress decisions shared by the listener and command acknowledgements. */
final class LectureRecordingPlan {
    enum Action { NONE, START, ROTATE, WAIT, STOP }
    private long version = -1;
    private String group = "";
    private boolean closed;

    Action update(long nextVersion, boolean live, boolean hasVisit, String nextGroup) {
        if (closed || nextVersion < version) return Action.NONE;
        version = nextVersion;
        if (!live) {
            closed = true;
            group = "";
            return Action.STOP;
        }
        String target = hasVisit ? nextGroup : "";
        if (target.equals(group)) return Action.NONE;
        String previous = group;
        group = target;
        if (target.isEmpty()) return Action.WAIT;
        return previous.isEmpty() ? Action.START : Action.ROTATE;
    }

    void close() { closed = true; group = ""; }
}
