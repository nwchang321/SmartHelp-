package com.smarthelp.app.session;

public final class TaskSessionLocal {

    public enum LocalState {
        IDLE,
        ACTIVE,
        COMPLETED,
        SENSITIVE_PAUSED
    }

    private LocalState state = LocalState.IDLE;
    private String currentQuery;
    private int stepCount;
    private boolean pendingVerify;
    private long referenceScreenshotHash;
    private int lastHighlightX = -1;
    private int lastHighlightY = -1;
    private int lastHighlightX1 = -1;
    private int lastHighlightY1 = -1;
    private int lastHighlightX2 = -1;
    private int lastHighlightY2 = -1;

    public void beginTask(String query) {
        currentQuery = query;
        stepCount = 0;
        pendingVerify = false;
        referenceScreenshotHash = 0L;
        clearLastHighlight();
        state = LocalState.ACTIVE;
    }

    public void incrementStepCount() {
        stepCount++;
        if (state != LocalState.SENSITIVE_PAUSED) {
            state = LocalState.ACTIVE;
        }
    }

    public void clearTask() {
        currentQuery = null;
        stepCount = 0;
        pendingVerify = false;
        referenceScreenshotHash = 0L;
        clearLastHighlight();
        state = LocalState.IDLE;
    }

    public void markCompleted() {
        currentQuery = null;
        stepCount = 0;
        pendingVerify = false;
        referenceScreenshotHash = 0L;
        clearLastHighlight();
        state = LocalState.COMPLETED;
    }

    public void pauseForSensitiveScreen() {
        pendingVerify = false;
        referenceScreenshotHash = 0L;
        state = LocalState.SENSITIVE_PAUSED;
    }

    public void resumeAfterSensitiveScreen() {
        if (state == LocalState.SENSITIVE_PAUSED) {
            state = hasActiveTask() ? LocalState.ACTIVE : LocalState.IDLE;
        }
    }

    public boolean hasActiveTask() {
        return currentQuery != null && !currentQuery.isEmpty();
    }

    public LocalState getState() {
        return state;
    }

    public String getCurrentQuery() {
        return currentQuery;
    }

    public int getStepCount() {
        return stepCount;
    }

    public boolean isPendingVerify() {
        return pendingVerify;
    }

    public void setPendingVerify(boolean pendingVerify) {
        this.pendingVerify = pendingVerify;
    }

    public long getReferenceScreenshotHash() {
        return referenceScreenshotHash;
    }

    public void setReferenceScreenshotHash(long referenceScreenshotHash) {
        this.referenceScreenshotHash = referenceScreenshotHash;
    }

    public void setLastHighlight(int x, int y) {
        setLastHighlight(x, y, -1, -1, -1, -1);
    }

    public void setLastHighlight(int x, int y, int x1, int y1, int x2, int y2) {
        lastHighlightX = x;
        lastHighlightY = y;
        lastHighlightX1 = x1;
        lastHighlightY1 = y1;
        lastHighlightX2 = x2;
        lastHighlightY2 = y2;
    }

    public void clearLastHighlight() {
        lastHighlightX = -1;
        lastHighlightY = -1;
        lastHighlightX1 = -1;
        lastHighlightY1 = -1;
        lastHighlightX2 = -1;
        lastHighlightY2 = -1;
    }

    public int getLastHighlightX() {
        return lastHighlightX;
    }

    public int getLastHighlightY() {
        return lastHighlightY;
    }

    public int getLastHighlightX1() {
        return lastHighlightX1;
    }

    public int getLastHighlightY1() {
        return lastHighlightY1;
    }

    public int getLastHighlightX2() {
        return lastHighlightX2;
    }

    public int getLastHighlightY2() {
        return lastHighlightY2;
    }

    public boolean hasLastHighlightBounds() {
        return lastHighlightX1 >= 0 && lastHighlightY1 >= 0 && lastHighlightX2 >= 0 && lastHighlightY2 >= 0;
    }

    public boolean hasLastHighlight() {
        return lastHighlightX >= 0 && lastHighlightY >= 0;
    }
}
