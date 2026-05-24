package com.smarthelp.app;

import android.view.View;
import android.view.WindowManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class OverlayWindowHelperTest {

    @Mock WindowManager mockWindowManager;
    @Mock View mockView;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void build_callsAddView() {
        new OverlayWindowHelper(mockWindowManager)
                .size(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                .passThrough(true)
                .build(mockView);

        verify(mockWindowManager, times(1)).addView(eq(mockView), any(WindowManager.LayoutParams.class));
    }

    @Test
    public void build_passThroughTrue_setsNotTouchableFlag() {
        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);

        new OverlayWindowHelper(mockWindowManager)
                .passThrough(true)
                .build(mockView);

        verify(mockWindowManager).addView(eq(mockView), captor.capture());
        int flags = captor.getValue().flags;
        assertTrue("FLAG_NOT_TOUCHABLE should be set",
                (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
    }

    @Test
    public void show_removesPassThroughFlags_andSetsVisible() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager)
                .passThrough(true)
                .build(mockView);

        helper.show();

        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mockWindowManager).updateViewLayout(eq(mockView), captor.capture());

        int flags = captor.getValue().flags;
        assertEquals("FLAG_NOT_TOUCHABLE should be cleared on show()",
                0, flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        verify(mockView).setVisibility(View.VISIBLE);
    }

    @Test
    public void hide_addsPassThroughFlags_andSetsGone() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager)
                .passThrough(false)
                .build(mockView);

        helper.hide();

        // View gone first, then updateViewLayout
        verify(mockView).setVisibility(View.GONE);

        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mockWindowManager).updateViewLayout(eq(mockView), captor.capture());

        int flags = captor.getValue().flags;
        assertTrue("FLAG_NOT_TOUCHABLE should be set after hide()",
                (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
    }

    @Test
    public void dismiss_callsRemoveView() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager)
                .build(mockView);

        helper.dismiss();

        verify(mockWindowManager).removeView(mockView);
        assertFalse(helper.isAdded());
    }

    @Test
    public void dismiss_afterAlreadyDismissed_doesNotCrash() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager)
                .build(mockView);

        helper.dismiss();
        helper.dismiss(); // second call must be a no-op

        verify(mockWindowManager, times(1)).removeView(any()); // only once
    }

    @Test
    public void show_beforeBuild_doesNotCrash() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager);
        helper.show(); // no build() called — must be a safe no-op
        verifyNoInteractions(mockWindowManager);
    }

    @Test
    public void moveTo_updatesParamsXY() {
        OverlayWindowHelper helper = new OverlayWindowHelper(mockWindowManager)
                .build(mockView);

        helper.moveTo(100, 200);

        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mockWindowManager).updateViewLayout(eq(mockView), captor.capture());
        assertEquals(100, captor.getValue().x);
        assertEquals(200, captor.getValue().y);
    }
}
