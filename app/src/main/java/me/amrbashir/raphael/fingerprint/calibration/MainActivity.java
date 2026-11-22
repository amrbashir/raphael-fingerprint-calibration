package me.amrbashir.raphael.fingerprint.calibration;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import com.goodix.fingerprint.setting.DualRubberTestActivity;

/** Stock calibration activity, with fullscreen compatibility for the supported Android versions. */
public final class MainActivity extends DualRubberTestActivity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        configureFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) configureFullscreen();
    }

    void configureFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Keep sensor coordinates relative to the whole display, including the system-bar areas.
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

}
