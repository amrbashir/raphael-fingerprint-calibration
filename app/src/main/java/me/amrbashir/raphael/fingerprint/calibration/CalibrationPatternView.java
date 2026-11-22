package me.amrbashir.raphael.fingerprint.calibration;

import android.graphics.*;
import android.view.View;
import java.io.*;

public final class CalibrationPatternView extends View {
        int x, y, w, h;
        boolean visible, chart;
        String ack;

        final Bitmap gradient;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        private final java.util.function.Consumer<String> onDrawn;

        public CalibrationPatternView(android.content.Context context, java.util.function.Consumer<String> onDrawn) {
            super(context);
            this.onDrawn = onDrawn;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            try (InputStream input = getContext().getAssets().open("gradient_spot_190.png")) {
                gradient = BitmapFactory.decodeStream(input);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        public void setGeometry(int left, int top, int width, int height) {
            x = left; y = top; w = width; h = height;
            invalidate();
        }

        public void showSensor(boolean show) {
            visible = show;
            invalidate();
        }

        public void showPicture(String mode) {
            ack = mode;
            chart = mode.equals("CHART");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!visible) return;

            if (!chart)
                canvas.drawBitmap(gradient, null, new Rect(x, y, x + w, y + h), paint);
            else {
                int stripe = (int) Math.round(getResources().getDisplayMetrics().densityDpi / 25.4d * 0.2d);
                canvas.save();
                canvas.clipRect(x, y, x + w, y + h);

                float position = x - w / 2 - stripe / 2;
                int count = 1;
                paint.setStrokeWidth(stripe);

                while (position <= x + w * 3 / 2 + stripe) {
                    position += stripe;
                    paint.setColor((count++ & 1) == 1 ? Color.GREEN : Color.BLACK);
                    canvas.drawLine(position, y - h / 2, position, y + h * 3 / 2, paint);
                }
                canvas.restore();
            }

            if (ack != null) {
                String mode = ack;
                ack = null;
                postOnAnimation(() -> postDelayed(() -> onDrawn.accept(mode), 150));
            }
        }
    }
