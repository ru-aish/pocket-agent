package dev.pocket.agent;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class PocketIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    private int requestedAlpha=255;
    private final int ink, accent;
    PocketIcon(String name, int ink, int accent) { this.name = name; this.ink = ink; this.accent = accent; }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); canvas.translate(getBounds().left, getBounds().top); canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        if (name.equals("brand")) { paint.setStyle(Paint.Style.FILL); paint.setColor(accent); paint.setAlpha(android.graphics.Color.alpha(accent)*requestedAlpha/255); canvas.drawRoundRect(0, 0, 24, 24, 7, 7, paint); }
        paint.setColor(ink); paint.setAlpha(android.graphics.Color.alpha(ink)*requestedAlpha/255); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(name.equals("brand") ? 1.25f : 1.6f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        switch (name) {
            case "menu" -> { path.moveTo(5, 7); path.lineTo(19, 7); path.moveTo(5, 12); path.lineTo(15, 12); path.moveTo(5, 17); path.lineTo(19, 17); }
            case "mic" -> { canvas.drawRoundRect(9, 3, 15, 15, 3, 3, paint); path.moveTo(5, 11); path.cubicTo(5, 21, 19, 21, 19, 11); path.moveTo(12, 19); path.lineTo(12, 22); }
            case "send" -> { path.moveTo(12, 19); path.lineTo(12, 5); path.moveTo(6, 11); path.lineTo(12, 5); path.lineTo(18, 11); }
            case "close" -> { path.moveTo(6, 6); path.lineTo(18, 18); path.moveTo(18, 6); path.lineTo(6, 18); }
            case "back" -> { path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19); }
            case "more" -> { for (int x : new int[]{5, 12, 19}) canvas.drawCircle(x, 12, 1, paint); }
            default -> { path.moveTo(6, 5); path.lineTo(18, 5); path.lineTo(18, 14); path.cubicTo(18, 18, 12, 21, 12, 21); path.cubicTo(12, 21, 6, 18, 6, 14); path.close(); path.moveTo(9, 10); path.lineTo(12, 13); path.lineTo(15, 10); }
        }
        canvas.drawPath(path, paint); canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { requestedAlpha=alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
