package com.example.scrcpyandroidfullproto.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

import androidx.annotation.Nullable;

public class AspectRatioSurfaceView extends SurfaceView {
    private float aspectRatio = 0f;
    private boolean stretchToFill;

    public AspectRatioSurfaceView(Context context) {
        super(context);
    }

    public AspectRatioSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioSurfaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Sets the target aspect ratio. Use zero or negative to disable ratio enforcement.
     */
    public void setAspectRatio(int width, int height) {
        float ratio = (width > 0 && height > 0) ? (float) width / height : 0f;
        if (Math.abs(ratio - aspectRatio) < 0.0001f) {
            return;
        }
        aspectRatio = ratio;
        requestLayout();
    }

    public void setStretchToFill(boolean stretch) {
        if (stretch == stretchToFill) {
            return;
        }
        stretchToFill = stretch;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (stretchToFill) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            return;
        }

        if (aspectRatio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        if (widthMode == MeasureSpec.UNSPECIFIED && heightMode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int measuredWidth = width;
        int measuredHeight = (int) (width / aspectRatio);
        if (heightMode != MeasureSpec.UNSPECIFIED && measuredHeight > height) {
            measuredHeight = height;
            measuredWidth = (int) (height * aspectRatio);
        }

        if (widthMode != MeasureSpec.EXACTLY) {
            measuredWidth = Math.min(measuredWidth, width);
        }
        if (heightMode != MeasureSpec.EXACTLY) {
            measuredHeight = Math.min(measuredHeight, height);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }
}
