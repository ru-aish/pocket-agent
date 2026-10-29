package dev.pocket.agent;
import android.view.Surface;
interface IDisplayShell {
    int createDisplay(int width, int height, int dpi) = 1;
    void removeDisplay(int displayId) = 2;
    boolean launchActivity(int displayId, String componentName) = 3;
    boolean injectKey(int displayId, int keyCode) = 4;
    boolean openUrl(int displayId, String url) = 6;
    boolean showClock(int displayId, String screen) = 7;
    boolean repairAccessibility(String componentName, boolean restoreMissing) = 8;
    void showPreview(int displayId, in Surface surface, IBinder session) = 9;
    void hidePreview(int displayId, IBinder session) = 10;
    String health() = 5;
    void destroy() = 16777114;
}
