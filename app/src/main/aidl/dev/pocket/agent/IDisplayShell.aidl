package dev.pocket.agent;
interface IDisplayShell {
    int createDisplay(int width, int height, int dpi) = 1;
    void removeDisplay(int displayId) = 2;
    boolean launchActivity(int displayId, String componentName) = 3;
    boolean injectKey(int displayId, int keyCode) = 4;
    boolean openUrl(int displayId, String url) = 6;
    boolean showClock(int displayId, String screen) = 7;
    boolean repairAccessibility(String componentName, boolean restoreMissing) = 8;
    String terminal(String request, String owner, int displayId) = 9;
    String health() = 5;
    String previewDisplay(int displayId) = 11;
    boolean injectText(int displayId, String text) = 12;
    void destroy() = 16777114;
}
