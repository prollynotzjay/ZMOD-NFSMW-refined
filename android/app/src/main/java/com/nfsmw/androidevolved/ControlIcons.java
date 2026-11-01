package com.nfsmw.androidevolved;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * One hand-drawn vector icon per control, in the control's own accent colour (white while pressed on the
 * amber face... dark ink). Everything is drawn from paths, so it stays sharp at any size and needs no assets.
 */
final class ControlIcons {
    static final int AMBER = 0xFFFFB000, INK_ON = 0xFF1A1100;

    private ControlIcons() {}

    static int accent(String icon) {
        switch (icon) {
            case "gas": return 0xFF3DDC84;
            case "brake": return 0xFFFF5252;
            case "nitro": return 0xFF35C8FF;
            case "handbrake": return 0xFFFF8A3D;
            case "speedbreaker": return 0xFFB58CFF;
            case "accept": return 0xFF7CD992;
            case "back": return 0xFFFF7B7B;
            case "reset": return 0xFFFFD166;
            case "map": return 0xFF7FD6FF;
            case "pause": return 0xFFFFFFFF;
            case "shiftUp": return 0xFFFFE08A;
            case "shiftDown": return 0xFFFFE08A;
            case "camera": return 0xFFE6E6E6;
            case "left": case "right": return 0xFFFFFFFF;
            default: return 0xFFF2F2F2;
        }
    }

    static void draw(Canvas c, String icon, float cx, float cy, float r, boolean on, Paint line, Paint fill, Path p) {
        float s = r * .46f;
        int ink = on ? INK_ON : accent(icon);
        line.setColor(ink); fill.setColor(ink);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(2.2f, r * .11f));
        switch (icon) {
            case "left": case "right": {          // two chevrons with speed lines
                boolean l = icon.equals("left");
                chevronSide(c, p, line, cx - (l ? -1 : 1) * s * .55f, cy, s * .85f, l);
                chevronSide(c, p, line, cx + (l ? -1 : 1) * s * .3f, cy, s * .85f, l);
                break;
            }
            case "up": chevron(c, p, line, cx, cy, s, true); break;
            case "down": chevron(c, p, line, cx, cy, s, false); break;
            case "gas": chevron(c, p, line, cx, cy - s * .4f, s, true); chevron(c, p, line, cx, cy + s * .4f, s, true); break;
            case "brake":
                chevron(c, p, line, cx, cy - s * .35f, s, false);
                c.drawLine(cx - s, cy + s * .55f, cx + s, cy + s * .55f, line);
                break;
            case "nitro": {                        // flame with a bright core
                flame(c, p, cx, cy, s * 1.05f, line);
                fill.setStyle(Paint.Style.FILL);
                flame(c, p, cx, cy + s * .22f, s * .5f, fill);
                break;
            }
            case "speedbreaker":                   // stopwatch with a lightning bolt
                c.drawCircle(cx, cy + s * .15f, s * .78f, line);
                c.drawLine(cx, cy - s * .63f, cx, cy - s * .92f, line);
                c.drawLine(cx - s * .28f, cy - s * .95f, cx + s * .28f, cy - s * .95f, line);
                p.reset(); p.moveTo(cx + s * .12f, cy - s * .32f); p.lineTo(cx - s * .28f, cy + s * .22f);
                p.lineTo(cx + s * .05f, cy + s * .22f); p.lineTo(cx - s * .12f, cy + s * .62f);
                c.drawPath(p, line);
                break;
            case "handbrake":                      // brake-warning symbol: P in brackets
                c.drawCircle(cx, cy, s * .6f, line);
                c.drawArc(cx - s * .98f, cy - s * .98f, cx + s * .98f, cy + s * .98f, 135, 90, false, line);
                c.drawArc(cx - s * .98f, cy - s * .98f, cx + s * .98f, cy + s * .98f, -45, 90, false, line);
                c.drawLine(cx - s * .16f, cy + s * .34f, cx - s * .16f, cy - s * .34f, line);
                c.drawArc(cx - s * .16f, cy - s * .34f, cx + s * .26f, cy + s * .04f, -90, 180, false, line);
                break;
            case "shiftUp": case "shiftDown": {    // gear with an arrow
                boolean up = icon.equals("shiftUp");
                c.drawCircle(cx, cy, s * .62f, line);
                for (int i = 0; i < 8; ++i) {
                    double a = i * Math.PI / 4;
                    c.drawLine(cx + (float) Math.cos(a) * s * .62f, cy + (float) Math.sin(a) * s * .62f,
                            cx + (float) Math.cos(a) * s * .95f, cy + (float) Math.sin(a) * s * .95f, line);
                }
                chevron(c, p, line, cx, cy + (up ? -s * .05f : s * .05f), s * .3f, up);
                break;
            }
            case "camera":
                c.drawRoundRect(cx - s * .9f, cy - s * .5f, cx + s * .9f, cy + s * .62f, s * .22f, s * .22f, line);
                c.drawCircle(cx, cy + s * .06f, s * .32f, line);
                fill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(cx - s * .4f, cy - s * .78f, cx + s * .1f, cy - s * .5f, s * .08f, s * .08f, fill);
                break;
            case "pause":
                fill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(cx - s * .6f, cy - s * .7f, cx - s * .16f, cy + s * .7f, s * .12f, s * .12f, fill);
                c.drawRoundRect(cx + s * .16f, cy - s * .7f, cx + s * .6f, cy + s * .7f, s * .12f, s * .12f, fill);
                break;
            case "reset":                          // respot: circular arrow around a car dot
                c.drawArc(cx - s * .8f, cy - s * .8f, cx + s * .8f, cy + s * .8f, -50, 280, false, line);
                p.reset(); p.moveTo(cx + s * .2f, cy - s); p.lineTo(cx + s * .62f, cy - s * .62f); p.lineTo(cx + s * .1f, cy - s * .42f);
                c.drawPath(p, line);
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * .16f, fill);
                break;
            case "map": {                          // map pin
                p.reset(); p.moveTo(cx, cy + s * .95f);
                p.cubicTo(cx - s * .2f, cy + s * .45f, cx - s * .8f, cy + s * .2f, cx - s * .8f, cy - s * .25f);
                p.cubicTo(cx - s * .8f, cy - s * .75f, cx - s * .4f, cy - s * .95f, cx, cy - s * .95f);
                p.cubicTo(cx + s * .4f, cy - s * .95f, cx + s * .8f, cy - s * .75f, cx + s * .8f, cy - s * .25f);
                p.cubicTo(cx + s * .8f, cy + s * .2f, cx + s * .2f, cy + s * .45f, cx, cy + s * .95f);
                c.drawPath(p, line);
                c.drawCircle(cx, cy - s * .25f, s * .28f, line);
                break;
            }
            case "sms":
                c.drawRoundRect(cx - s * .92f, cy - s * .7f, cx + s * .92f, cy + s * .4f, s * .28f, s * .28f, line);
                p.reset(); p.moveTo(cx - s * .45f, cy + s * .4f); p.lineTo(cx - s * .62f, cy + s * .88f); p.lineTo(cx - s * .02f, cy + s * .4f);
                c.drawPath(p, line);
                fill.setStyle(Paint.Style.FILL);
                for (int dot = -1; dot <= 1; ++dot) c.drawCircle(cx + dot * s * .42f, cy - s * .15f, s * .1f, fill);
                break;
            case "menu":                           // switch to menu controls: four tiles
                fill.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; ++i) {
                    float x = cx + (i % 2 == 0 ? -s * .5f : s * .5f), y = cy + (i < 2 ? -s * .5f : s * .5f);
                    c.drawRoundRect(x - s * .36f, y - s * .36f, x + s * .36f, y + s * .36f, s * .1f, s * .1f, fill);
                }
                break;
            case "wheel":                          // switch to driving controls: steering wheel
                c.drawCircle(cx, cy, s * .92f, line);
                c.drawCircle(cx, cy, s * .2f, line);
                c.drawLine(cx - s * .92f, cy, cx - s * .2f, cy, line);
                c.drawLine(cx + s * .2f, cy, cx + s * .92f, cy, line);
                c.drawLine(cx, cy + s * .2f, cx, cy + s * .92f, line);
                break;
            case "settings":                       // sliders
                fill.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; ++i) {
                    float y = cy + i * s * .62f, k = cx + (i == 0 ? s * .35f : -s * .35f);
                    c.drawLine(cx - s * .88f, y, cx + s * .88f, y, line);
                    c.drawCircle(k, y, s * .24f, fill);
                }
                break;
            case "accept":
                p.reset(); p.moveTo(cx - s * .75f, cy + s * .02f); p.lineTo(cx - s * .18f, cy + s * .6f); p.lineTo(cx + s * .8f, cy - s * .55f);
                c.drawPath(p, line);
                break;
            case "back":
                c.drawLine(cx - s * .55f, cy - s * .55f, cx + s * .55f, cy + s * .55f, line);
                c.drawLine(cx + s * .55f, cy - s * .55f, cx - s * .55f, cy + s * .55f, line);
                break;
            case "keyboard":
                c.drawRoundRect(cx - s * .95f, cy - s * .6f, cx + s * .95f, cy + s * .6f, s * .15f, s * .15f, line);
                fill.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; ++row) for (int col = 0; col < 4; ++col)
                    c.drawCircle(cx - s * .54f + col * s * .36f, cy - s * .22f + row * s * .3f, s * .07f, fill);
                c.drawLine(cx - s * .4f, cy + s * .34f, cx + s * .4f, cy + s * .34f, line);
                break;
            default:
                break;
        }
        fill.setStyle(Paint.Style.FILL);
    }

    static void chevron(Canvas c, Path p, Paint line, float x, float y, float s, boolean up) {
        float d = up ? -1 : 1;
        p.reset(); p.moveTo(x - s, y - d * s * .45f); p.lineTo(x, y + d * s * .45f); p.lineTo(x + s, y - d * s * .45f);
        c.drawPath(p, line);
    }

    static void chevronSide(Canvas c, Path p, Paint line, float x, float y, float s, boolean left) {
        float d = left ? -1 : 1;
        p.reset(); p.moveTo(x - d * s * .45f, y - s); p.lineTo(x + d * s * .45f, y); p.lineTo(x - d * s * .45f, y + s);
        c.drawPath(p, line);
    }

    private static void flame(Canvas c, Path p, float cx, float cy, float s, Paint paint) {
        p.reset(); p.moveTo(cx, cy - s);
        p.cubicTo(cx + s * .25f, cy - s * .55f, cx + s * .95f, cy - s * .1f, cx + s * .7f, cy + s * .45f);
        p.cubicTo(cx + s * .55f, cy + s * .85f, cx + s * .2f, cy + s * .95f, cx, cy + s * .95f);
        p.cubicTo(cx - s * .2f, cy + s * .95f, cx - s * .75f, cy + s * .8f, cx - s * .7f, cy + s * .3f);
        p.cubicTo(cx - s * .65f, cy - s * .05f, cx - s * .3f, cy - s * .2f, cx - s * .2f, cy - s * .55f);
        p.cubicTo(cx - s * .1f, cy - s * .8f, cx, cy - s * .9f, cx, cy - s);
        p.close();
        c.drawPath(p, paint);
    }
}
