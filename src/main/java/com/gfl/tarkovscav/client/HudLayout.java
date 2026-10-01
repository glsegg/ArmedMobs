package com.gfl.tarkovscav.client;

import java.util.ArrayList;
import java.util.List;

/** Shared, GUI-pixel layout for this frame. Existing vanilla panels reserve space first. */
public final class HudLayout {
    private static final int MARGIN = 4;
    private static final int GAP = 4;
    private static final List<Rect> OCCUPIED = new ArrayList<>();
    private static int width;
    private static int bottom;

    private HudLayout() { }

    public record Rect(int left, int top, int width, int height) {
        public int right() { return left + width; }
        public int bottom() { return top + height; }
        boolean overlaps(Rect other) {
            return left < other.right() + GAP && right() + GAP > other.left
                    && top < other.bottom() + GAP && bottom() + GAP > other.top;
        }
    }

    public record Panel(Rect bounds, int rows) { }

    public static void beginFrame(int screenWidth, int screenHeight) {
        width = screenWidth;
        // Leave the crosshair and the lower gameplay HUD clear, even at a large GUI scale.
        bottom = Math.max(MARGIN, screenHeight / 2 - 12);
        OCCUPIED.clear();
    }

    public static void reserve(Rect bounds) {
        if (bounds.width > 0 && bounds.height > 0) OCCUPIED.add(bounds);
    }

    /** Reduce rows before allowing a panel to cover another HUD or leave the viewport. */
    public static Panel allocate(String position, int requestedWidth, int rowHeight, int rows, int minRows) {
        int panelWidth = Math.min(Math.max(1, requestedWidth), width - 2 * MARGIN);
        if (panelWidth <= 0 || rowHeight <= 0) return null;
        String[] anchors = {position, "top_left", "top_right", "top_center"};
        for (int count = rows; count >= minRows; count--) {
            int panelHeight = count * rowHeight + 4;
            for (String anchor : anchors) {
                int left = switch (anchor) {
                    case "top_left" -> MARGIN;
                    case "top_right" -> width - MARGIN - panelWidth;
                    default -> (width - panelWidth) / 2;
                };
                int top = MARGIN;
                while (top + panelHeight <= bottom) {
                    Rect candidate = new Rect(left, top, panelWidth, panelHeight);
                    int nextTop = top;
                    for (Rect occupied : OCCUPIED) {
                        if (candidate.overlaps(occupied)) nextTop = Math.max(nextTop, occupied.bottom() + GAP);
                    }
                    if (nextTop == top) {
                        reserve(candidate);
                        return new Panel(candidate, count);
                    }
                    top = nextTop;
                }
            }
        }
        return null;
    }
}
