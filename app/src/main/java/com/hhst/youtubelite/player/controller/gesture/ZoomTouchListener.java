package com.hhst.youtubelite.player.controller.gesture;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.hhst.youtubelite.player.LitePlayerView;

import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Setter;

/**
 * Handles pinch-to-zoom gesture on the player, toggling between
 * RESIZE_MODE_FIT and RESIZE_MODE_FIXED_WIDTH with a YouTube-style animation.
 */
@ActivityScoped
@UnstableApi
public class ZoomTouchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

	private static final float ZOOM_IN_THRESHOLD  = 1.15f; // pinch-out threshold to trigger zoom
	private static final float ZOOM_OUT_THRESHOLD = 0.88f; // pinch-in threshold to trigger unzoom
	private static final long  ANIM_DURATION_MS   = 200L;

	private final ScaleGestureDetector detector;
	private final LitePlayerView playerView;

	@Setter
	private Consumer<Boolean> onShowReset;

	/** Accumulated scale factor during an active pinch gesture. */
	private float pinchFactor = 1.0f;
	/** Whether we are currently in FIXED_WIDTH (zoomed) mode. */
	private boolean zoomed = false;
	/** Ongoing animator so we can cancel mid-flight. */
	private ValueAnimator currentAnim = null;

	@Inject
	public ZoomTouchListener(Activity activity, LitePlayerView playerView) {
		this.playerView = playerView;
		this.detector = new ScaleGestureDetector(activity, this);
	}

	// Called by Controller for every touch event that GestureDetector didn't consume.
	public void onTouch(MotionEvent event) {
		detector.onTouchEvent(event);

		// Reset pinchFactor when all fingers lift.
		if (event.getPointerCount() < 2
				&& event.getActionMasked() == MotionEvent.ACTION_UP) {
			pinchFactor = 1.0f;
		}
	}

	@Override
	public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
		pinchFactor = 1.0f;
		return true;
	}

	@Override
	public boolean onScale(@NonNull ScaleGestureDetector detector) {
		pinchFactor *= detector.getScaleFactor();
		pinchFactor = Math.max(0.5f, Math.min(pinchFactor, 2.5f));

		if (!zoomed && pinchFactor >= ZOOM_IN_THRESHOLD) {
			// Pinch-out: animate to FIXED_WIDTH
			animateToZoomed(true);
		} else if (zoomed && pinchFactor <= ZOOM_OUT_THRESHOLD) {
			// Pinch-in: animate back to FIT
			animateToZoomed(false);
		}
		return true;
	}

	@Override
	public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
		pinchFactor = 1.0f;
	}

	/** Programmatic reset (e.g. from the reset button). */
	public void reset() {
		if (!zoomed) return;
		animateToZoomed(false);
	}

	public boolean isZoomed() {
		return zoomed;
	}

	// ── animation ────────────────────────────────────────────────────────────

	private void animateToZoomed(boolean toZoom) {
		if (zoomed == toZoom) return;
		zoomed = toZoom;

		View target = getTargetView();
		if (target == null) {
			applyResizeMode(toZoom);
			notifyResetVisibility();
			return;
		}

		// Decide start / end scale for the visual "pop" effect.
		float fromScale = toZoom ? 1.0f : 1.08f;
		float toScale   = toZoom ? 1.08f : 1.0f;

		// Switch the ExoPlayer resize mode immediately so the video fills the frame.
		applyResizeMode(toZoom);

		// Cancel any running animation.
		if (currentAnim != null) currentAnim.cancel();

		ValueAnimator anim = ValueAnimator.ofFloat(fromScale, toScale);
		anim.setDuration(ANIM_DURATION_MS);
		anim.setInterpolator(new DecelerateInterpolator());
		anim.addUpdateListener(a -> {
			float s = (float) a.getAnimatedValue();
			target.setScaleX(s);
			target.setScaleY(s);
		});
		anim.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				// Settle to 1.0 so the view doesn't stay slightly over/under-scaled.
				target.setScaleX(1.0f);
				target.setScaleY(1.0f);
				notifyResetVisibility();
			}
		});
		currentAnim = anim;
		anim.start();
	}

	private void applyResizeMode(boolean zoom) {
		int mode = zoom
				? AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
				: AspectRatioFrameLayout.RESIZE_MODE_FIT;
		playerView.setResizeMode(mode);
	}

	private void notifyResetVisibility() {
		if (onShowReset != null) onShowReset.accept(zoomed);
	}

	private View getTargetView() {
		View contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
		if (contentFrame != null) return contentFrame;
		View surface = playerView.getVideoSurfaceView();
		if (surface != null) return surface;
		if (playerView.getChildCount() > 0) return playerView.getChildAt(0);
		return playerView;
	}
}
