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
 * Adds a download button to the left of the like button in the story viewer footer.
 * The like container is moved into a horizontal row [download, like] that takes its place,
 * layout params and id, so the footer lays out the same whatever layout it is.
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

            // The row has the like container's id and comes first in the tree, so it's found first once added.
            View found = footer.findViewById(likeContainerId);
            if (found == null) return;
            LinearLayout row;
            if (ROW_TAG.equals(found.getTag())) {
                row = (LinearLayout) found;
            } else {
                if (!show) return;
                row = wrap(found);
            }

            ImageView button = (ImageView) row.getChildAt(0);
            button.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) return;
            button.setOnClickListener(v ->
                    DownloadUtils.downloadCurrentMedia(PostUfiButtons.getActivity(v), userSession, media, 0));
            button.setOnLongClickListener(v -> {
                DownloadUtils.showDownloadMenu(PostUfiButtons.getActivity(v), userSession, media, 0);
                return true;
            });
        } catch (Exception e) {
            unavailable = true;
            Logger.printException(() -> "StoryDownloadButton bind failure", e);
        }
    }

    private static LinearLayout wrap(View likeContainer) {
        ViewGroup parent = (ViewGroup) likeContainer.getParent();
        Context context = likeContainer.getContext();
        ViewGroup.LayoutParams params = likeContainer.getLayoutParams();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setId(likeContainer.getId());
        row.setTag(ROW_TAG);

        // Same size and padding as the like button, so the two line up.
        View likeButton = likeButtonId == 0 ? null : likeContainer.findViewById(likeButtonId);
        ViewGroup.LayoutParams likeButtonParams = likeButton == null ? null : likeButton.getLayoutParams();
        int size = likeButtonParams != null && likeButtonParams.width > 0
                ? likeButtonParams.width
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

        // A fixed width would squeeze both buttons into the like button's space.
        LinearLayout.LayoutParams likeParams = params.width > 0
                ? new LinearLayout.LayoutParams(params.width, ViewGroup.LayoutParams.WRAP_CONTENT)
                : params.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (params.width > 0) params.width = ViewGroup.LayoutParams.WRAP_CONTENT;

        int index = parent.indexOfChild(likeContainer);
        parent.removeViewAt(index);
        row.addView(button, new LinearLayout.LayoutParams(size, size));
        row.addView(likeContainer, likeParams);
        parent.addView(row, index, params);
        return row;
    }
}
