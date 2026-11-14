package fr.lettrine.game;

import android.content.ClipDescription;
import android.graphics.Color;
import android.view.DragEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

final class RackDrop {
    private final ViewKit ui;
    private final LinearLayout row;
    private final List<? extends View> tiles;
    private final IntPredicate canMove;
    private final BiConsumer<Integer, Integer> move;
    private View highlighted;

    RackDrop(ViewKit ui, LinearLayout row, List<? extends View> tiles, IntPredicate canMove,
             BiConsumer<Integer, Integer> move) {
        this.ui = ui;
        this.row = row;
        this.tiles = tiles;
        this.canMove = canMove;
        this.move = move;
        row.setOnDragListener(this::onDrag);
    }

    void refreshActions() {
        List<View> ordered = orderedTiles();
        for (int index = 0; index < tiles.size(); index++) {
            View tile = tiles.get(index);
            if (tile == null) continue;
            int slot = ordered.indexOf(tile);
            addMoveAction(tile, index, slot <= 0 ? -1 : tiles.indexOf(ordered.get(slot - 1)),
                    R.id.rack_move_left, R.string.rack_move_left);
            addMoveAction(tile, index, slot < 0 || slot + 1 >= ordered.size() ? -1 : tiles.indexOf(ordered.get(slot + 1)),
                    R.id.rack_move_right, R.string.rack_move_right);
        }
    }

    private void addMoveAction(View tile, int source, int target, int id, int label) {
        if (target < 0 || !canMove.test(source)) {
            ViewCompat.removeAccessibilityAction(tile, id);
            return;
        }
        ViewCompat.replaceAccessibilityAction(tile,
                new AccessibilityActionCompat(id, ui.context.getString(label)), null, (view, arguments) -> {
                    if (!canMove.test(source)) return false;
                    move.accept(source, target);
                    return true;
                });
    }

    void reorderViews(int source, int target) {
        List<View> ordered = orderedTiles();
        int from = ordered.indexOf(tiles.get(source));
        int to = ordered.indexOf(tiles.get(target));
        if (from < 0 || to < 0 || from == to) return;
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        List<Integer> slots = new ArrayList<>(end - start + 1);
        for (int index = start; index <= end; index++) slots.add(row.indexOfChild(ordered.get(index)));
        for (int index = end; index >= start; index--) row.removeView(ordered.get(index));
        ordered.add(to, ordered.remove(from));
        for (int slot = 0; slot < slots.size(); slot++) row.addView(ordered.get(start + slot), slots.get(slot));
        ui.tick(row);
        refreshActions();
    }

    private List<View> orderedTiles() {
        List<View> ordered = new ArrayList<>(tiles.size());
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (tiles.contains(child)) ordered.add(child);
        }
        return ordered;
    }

    private boolean onDrag(View view, DragEvent event) {
        int source = event.getLocalState() instanceof Integer index ? index : -1;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED -> {
                return source >= 0 && canMove.test(source) && event.getClipDescription() != null
                        && event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            }
            case DragEvent.ACTION_DRAG_LOCATION -> {
                View target = closestTile(event.getX());
                highlight(canMove.test(source) && target != tiles.get(source) ? target : null);
            }
            case DragEvent.ACTION_DROP -> {
                View target = closestTile(event.getX());
                highlight(null);
                int index = target == null ? -1 : tiles.indexOf(target);
                if (index < 0 || index == source || !canMove.test(source)) return false;
                move.accept(source, index);
            }
            case DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> highlight(null);
            default -> { }
        }
        return true;
    }

    private View closestTile(float x) {
        float distance = Float.MAX_VALUE;
        View closest = null;
        for (View tile : tiles) {
            if (tile == null || tile.getParent() != row) continue;
            float candidate = Math.abs(x - tile.getLeft() - tile.getWidth() / 2f);
            if (candidate < distance) {
                closest = tile;
                distance = candidate;
            }
        }
        return closest;
    }

    private void highlight(View target) {
        if (highlighted == target) return;
        if (highlighted != null) highlighted.setForeground(null);
        highlighted = target;
        if (target == null) return;
        var ring = ui.shape(Color.TRANSPARENT, 9);
        ring.setStroke(ui.dp(2), ui.palette.accent());
        target.setForeground(ring);
    }
}
