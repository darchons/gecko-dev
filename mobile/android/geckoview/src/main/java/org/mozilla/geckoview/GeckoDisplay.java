/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.view.Surface;

/**
 * Applications use a GeckoDisplay instance to provide {@link GeckoSession} with a {@link Surface} for
 * displaying content. To ensure drawing only happens on a valid {@link Surface}, {@link GeckoSession}
 * will only use the provided {@link Surface} after {@link #surfaceChanged(Surface, int, int)} is
 * called and before {@link #surfaceDestroyed()} returns.
 */
public class GeckoDisplay {
    private final GeckoSession session;

    protected GeckoDisplay(final GeckoSession session) {
        this.session = session;
    }

    /**
     * Required callback. The display's Surface has been created or changed. Must be
     * called on the application main thread. GeckoSession may block this call to ensure
     * the Surface is valid while resuming drawing.
     *
     * @param surface The new Surface.
     * @param width New width of the Surface.
     * @param height New height of the Surface.
     */
    public void surfaceChanged(Surface surface, int width, int height) {
        if (session.getDisplay() == this) {
            session.onSurfaceChanged(surface, width, height);
        }
    }

    /**
     * Required callback. The display's Surface has been destroyed. Must be called on the
     * application main thread. GeckoSession may block this call to ensure the Surface is
     * valid while pausing drawing.
     */
    public void surfaceDestroyed() {
        if (session.getDisplay() == this) {
            session.onSurfaceDestroyed();
        }
    }

    /**
     * Optional callback. The display's coordinates on the screen has changed. Must be
     * called on the application main thread.
     *
     * @param left The X coordinate of the display on the screen, in screen pixels.
     * @param top The Y coordinate of the display on the screen, in screen pixels.
     */
    public void screenOriginChanged(final int left, final int top) {
        if (session.getDisplay() == this) {
            session.onScreenOriginChanged(left, top);
        }
    }

    /**
     * Return whether the display should be pinned on the screen. When pinned, the display
     * should not be moved on the screen due to animation, scrolling, etc. A common reason
     * for the display being pinned is when the user is dragging a selection caret inside
     * the display; normal user interaction would be disrupted in that case if the display
     * was moved on screen.
     *
     * @return True if display should be pinned on the screen.
     */
    public boolean shouldPinOnScreen() {
        return session.getDisplay() == this && session.shouldPinOnScreen();
    }

    /**
     * Optional callback. The display's transformation matrix has changed. Must be called
     * on the application main thread. To map a point in display coordinates to screen
     * coordinates, the point is multiplied with the transformation matrix and then added
     * to the screen origin.
     *
     * @param matrix The transformation matrix from display units to screen pixels,
     *               or null to use an identity matrix.
     * @see #screenOriginChanged(int, int)
     */
    public void transformationMatrixChanged(final Matrix matrix) {
        session.onTransformationMatrixChanged(matrix);
    }

    /**
     * Optional callback. Metrics for the display's screen has changed. Must be called on
     * the application main thread.
     *
     * @param metrics New metrics for the display's screen.
     */
    public void displayMetricsChanged(final DisplayMetrics metrics) {
        session.onDisplayMetricsChanged(metrics);
    }
}
