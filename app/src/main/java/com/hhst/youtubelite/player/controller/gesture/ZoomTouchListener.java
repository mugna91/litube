package com.hhst.youtubelite.player.controller.gesture;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.hhst.youtubelite.player.LitePlayerView;

import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Setter;

/**
 * Handles pinch-to-zoom: toggles RESIZE_MODE_FIT ↔ RESIZE_MODE_FIXED_WIDTH
 * with a YouTube-style animation. Also exposes isPinching() so other gesture
 * listeners can suppress single-touch handling during a pinch.
 */
@ActivityScoped
@UnstableApi
public class ZoomTouchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

	private static final float ZOOM_IN_THRESHOLD  = 1.12f;
	private static final float ZOOM_OUT_THRESHOLD = 0.90f;
	private static final long  ANIM_DURATION_MS   = 180L;

	private final ScaleGestureDetector detector;
	private final LitePlayerView playerView;

	@Setter
	private Consumer<Boolean> onShowReset;
	/** Called with true when zooming in, false when zooming out. */
	@Setter @Nullable
	private Consumer<Boolean> onZoomChanged;

	private float pinchFactor = 1.0f;
	private boolean zoomed = false;
	private boolean pinching = false;
	@Nullable
	private ValueAnimator currentAnim = null;

	@Inject
	public ZoomTouchListener(Activity activity, LitePlayerView playerView) {
		this.playerView = playerView;
		this.detector = new ScaleGestureDetector(activity, this);
	}

	/** Sync initial state with whatever resize mode the player already has. */
	public void syncState() {
		zoomed = playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH;
		pinchFactor = 1.0f;
	}

	/** True while a two-finger pinch is in progress. */
	public boolean isPinching() {
		return pinching;
	}

	public void onTouch(MotionEvent event) {
		detector.onTouchEvent(event);

		int pointers = event.getPointerCount();
		int action = event.getActionMasked();

		if (pointers >= 2) {
			pinching = true;
		} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
			pinching = false;
			pinchFactor = 1.0f;
		}
	}

	@Override
	public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
		pinchFactor = 1.0f;
		pinching = true;
		return true;
	}

	@Override
	public boolean onScale(@NonNull ScaleGestureDetector detector) {
		pinchFactor *= detector.getScaleFactor();
		pinchFactor = Math.max(0.5f, Math.min(pinchFactor, 3.0f));

		if (!zoomed && pinchFactor >= ZOOM_IN_THRESHOLD) {
			animateToZoomed(true);
		} else if (zoomed && pinchFactor <= ZOOM_OUT_THRESHOLD) {
			animateToZoomed(false);
		}
		return true;
	}

	@Override
	public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
		pinchFactor = 1.0f;
	}

	public void reset() {
		if (!zoomed) return;
		animateToZoomed(false);
	}

	public boolean isZoomed() {
		return zoomed;
	}

	// ── animation ─────────────────────────────────────────────────────────────

	private void animateToZoomed(boolean toZoom) {
		if (zoomed == toZoom) return;
		zoomed = toZoom;
		pinchFactor = 1.0f;

		View target = getTargetView();

		applyResizeMode(toZoom);
		if (onZoomChanged != null) onZoomChanged.accept(toZoom);
		notifyResetVisibility();

		if (target == null) return;

		if (currentAnim != null) currentAnim.cancel();

		float fromScale = toZoom ? 0.94f : 1.06f;
		float toScale   = 1.0f;

		ValueAnimator anim = ValueAnimator.ofFloat(fromScale, toScale);
		anim.setDuration(ANIM_DURATION_MS);
		anim.setInterpolator(new DecelerateInterpolator());
		anim.addUpdateListener(a -> {
			float s = (float) a.getAnimatedValue();
			target.setScaleX(s);
			target.setScaleY(s);
		});
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				target.setScaleX(1.0f);
				target.setScaleY(1.0f);
			}
		});
		currentAnim = anim;
		anim.start();
	}

	private void applyResizeMode(boolean zoom) {
		playerView.setResizeMode(zoom
				? AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
				: AspectRatioFrameLayout.RESIZE_MODE_FIT);
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
