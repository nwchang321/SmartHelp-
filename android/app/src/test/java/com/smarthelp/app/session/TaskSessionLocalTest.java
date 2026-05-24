package com.smarthelp.app.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskSessionLocalTest {

    @Test
    public void beginTask_resetsSessionState() {
        TaskSessionLocal session = new TaskSessionLocal();

        session.beginTask("open settings");

        assertEquals(TaskSessionLocal.LocalState.ACTIVE, session.getState());
        assertEquals("open settings", session.getCurrentQuery());
        assertEquals(0, session.getStepCount());
        assertFalse(session.isPendingVerify());
        assertFalse(session.hasLastHighlight());
    }

    @Test
    public void pauseForSensitiveScreen_preservesTaskButClearsVerificationState() {
        TaskSessionLocal session = new TaskSessionLocal();
        session.beginTask("open settings");
        session.setPendingVerify(true);
        session.setReferenceScreenshotHash(99L);

        session.pauseForSensitiveScreen();
        session.resumeAfterSensitiveScreen();

        assertEquals(TaskSessionLocal.LocalState.ACTIVE, session.getState());
        assertEquals("open settings", session.getCurrentQuery());
        assertFalse(session.isPendingVerify());
        assertEquals(0L, session.getReferenceScreenshotHash());
    }

    @Test
    public void markCompleted_clearsActiveTaskState() {
        TaskSessionLocal session = new TaskSessionLocal();
        session.beginTask("send message");
        session.incrementStepCount();
        session.setLastHighlight(12, 34);

        session.markCompleted();

        assertEquals(TaskSessionLocal.LocalState.COMPLETED, session.getState());
        assertFalse(session.hasActiveTask());
        assertEquals(0, session.getStepCount());
        assertFalse(session.hasLastHighlight());
    }
}
