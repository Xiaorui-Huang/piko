/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

import com.instagram.common.session.UserSession;

/**
 * Adds a download button after the rightmost icon of the story viewer footer (share).
 * That icon is moved into a horizontal row [icon, download] that takes its place and
 * layout params, so the footer lays out the same whatever layout it is. The row must not take over the
 * icon's id: Instagram looks that id up again (e.g. when the viewer closes) and casts the result.
 */
@SuppressWarnings("unused")
public class StoryDownloadButton {
    private static final String ROW_TAG = "piko_story_download_row";

    private static int likeContainerId;
    private static int likeButtonId;
    private static int downloadIconId;
    // Set when the footer can't be changed, so a broken hook doesn't report an error for every story.
    private static boolean unavailable;

    /** Called when the story footer is bound to a story item. */
    public static void bind(UserSession userSession, Object media, ViewGroup footer) {
        if (unavailable || footer == null) return;
        try {
            boolean show = media != null && Pref.enableDownload() && Pref.storyDownloadButton();

            if (likeContainerId == 0) {
                likeContainerId = ResourceUtils.getIdentifier(ResourceType.ID, "toolbar_like_container");
                likeButtonId = ResourceUtils.getIdentifier(ResourceType.ID, "toolbar_like_button");
                downloadIconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, UI.DRAWABLE_DOWNLOAD_ICON);
                if (likeContainerId == 0 || downloadIconId == 0) throw new IllegalStateException("Resources not found");
            }

            View existing = footer.findViewWithTag(ROW_TAG);
            if (existing != null) {
                configure((LinearLayout) existing, show, userSession, media);
            } else if (show) {
                addWhenLaidOut(footer, userSession, media, 0);
            }
        } catch (Exception e) {
            unavailable = true;
            Logger.printException(() -> "StoryDownloadButton bind failure", e);
        }
    }

    private static void configure(LinearLayout row, boolean show, UserSession userSession, Object media) {
        ImageView button = (ImageView) row.getChildAt(row.getChildCount() - 1);
        button.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        button.setOnClickListener(v -> DownloadButtonActions.tap(v, userSession, media, 0));
        button.setOnLongClickListener(v -> {
            DownloadButtonActions.longPress(v, userSession, media, 0);
            return true;
        });
    }

    /** The footer's layout differs per story, so the rightmost icon is found by position once it is laid out. */
    private static void addWhenLaidOut(ViewGroup footer, UserSession userSession, Object media, int attempt) {
        // Retry with a delay: right after a story opens the icons may not have been measured yet (width 0).
        footer.postDelayed(() -> {
            try {
                if (footer.findViewWithTag(ROW_TAG) != null) return;
                View target = footer.getWidth() > 0 ? rightmostIcon(footer, footer, footer.getWidth()) : null;
                if (target == null) {
                    if (attempt < 20) addWhenLaidOut(footer, userSession, media, attempt + 1);
                    return;
                }
                configure(wrap(target), true, userSession, media);
            } catch (Exception e) {
                unavailable = true;
                Logger.printException(() -> "StoryDownloadButton wrap failure", e);
            }
        }, attempt == 0 ? 0 : 100);
    }

    private static View rightmostIcon(ViewGroup footer, View view, int footerWidth) {
        View best = null;
        int bestRight = Integer.MIN_VALUE;
        int[] location = new int[2];
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(view);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v.getVisibility() != View.VISIBLE) continue;
            // Icons only: skips the reply box, which is clickable but wide. The like container counts even when
            // Instagram handles its taps elsewhere, so a story with only a like button still gets the download button.
            boolean icon = v.isClickable() || v.hasOnClickListeners() || v.getId() == likeContainerId;
            if (v != footer && icon && v.getWidth() > 0 && v.getWidth() < footerWidth / 4) {
                v.getLocationOnScreen(location);
                int right = location[0] + v.getWidth();
                if (right > bestRight) {
                    bestRight = right;
                    best = v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return best;
    }

    private static LinearLayout wrap(View likeContainer) {
        ViewGroup parent = (ViewGroup) likeContainer.getParent();
        Context context = likeContainer.getContext();
        ViewGroup.LayoutParams params = likeContainer.getLayoutParams();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(ROW_TAG);

        // Same size and padding as the like button, so the two line up.
        View likeButton = likeButtonId == 0 ? null : likeContainer.findViewById(likeButtonId);
        ViewGroup.LayoutParams likeButtonParams = likeButton == null ? null : likeButton.getLayoutParams();
        int size = likeButtonParams != null && likeButtonParams.width > 0
                ? likeButtonParams.width
                : likeContainer.getHeight() > 0 ? likeContainer.getHeight()
                : Math.round(44 * context.getResources().getDisplayMetrics().density);

        ImageView button = new ImageView(context);
        button.setImageResource(downloadIconId);
        button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(str("piko_download_current_media"));
        if (likeButton != null) {
            button.setPadding(likeButton.getPaddingLeft(), likeButton.getPaddingTop(),
                    likeButton.getPaddingRight(), likeButton.getPaddingBottom());
        }

        // The row has to be as wide as both icons whatever width the original had (fixed, 0dp or match parent),
        // otherwise the footer squeezes them together.
        LinearLayout.LayoutParams likeParams = params.width > 0
                ? new LinearLayout.LayoutParams(params.width, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT;

        int index = parent.indexOfChild(likeContainer);
        parent.removeViewAt(index);
        row.addView(likeContainer, likeParams);
        row.addView(button, new LinearLayout.LayoutParams(size, size));
        parent.addView(row, index, params);
        return row;
    }
}
