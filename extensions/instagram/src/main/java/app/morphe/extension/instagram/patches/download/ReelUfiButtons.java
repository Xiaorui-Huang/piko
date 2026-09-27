/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.lang.reflect.Method;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

import com.instagram.common.session.UserSession;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Adds a download button above the more button in the Reels UFI column (like/comment/share/more).
 * The patch builds it with a copy of the more button's builder, which calls into this class
 * for the click handlers and icon.
 */
@SuppressWarnings("unused")
public class ReelUfiButtons {
    private static final String MEDIA_CLASS = "com.instagram.feed.media.Media";

    // Handlers of the button being built, from prepare() to the click modifiers of the same builder.
    private static final ThreadLocal<Function1<Object, Unit>> clickHandler = new ThreadLocal<>();

    private static Method mediaGetter;
    private static int downloadIconId;

    // The reel item has several media getters; the one from its interface returns the reel's own media.
    private static Object getMedia(Object item) throws Exception {
        if (mediaGetter == null) {
            for (Class<?> type : item.getClass().getInterfaces()) {
                for (Method method : type.getMethods()) {
                    if (method.getParameterTypes().length == 0 && method.getReturnType().getName().equals(MEDIA_CLASS)) {
                        mediaGetter = method;
                    }
                }
            }
            if (mediaGetter == null) throw new IllegalStateException("Media getter not found");
        }
        return mediaGetter.invoke(item);
    }

    /**
     * Called at the start of the copied builder. Returns false to skip the button; otherwise the
     * builder picks up the handlers from {@link #getClickHandler()} and {@link #getLongClickHandler()}.
     */
    public static boolean prepare(UserSession userSession, Object item) {
        clickHandler.remove();
        if (item == null || !Pref.enableDownload() || !Pref.reelDownloadButton()) return false;
        try {
            Object media = getMedia(item);
            if (media == null) return false;
            clickHandler.set(PostUfiButtons.createClickHandler(userSession, media, null));
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "ReelUfiButtons prepare failure", e);
            return false;
        }
    }

    public static Function1<Object, Unit> getClickHandler() {
        return clickHandler.get();
    }

    public static Function1<Object, Boolean> getLongClickHandler() {
        Function1<Object, Unit> handler = clickHandler.get();
        clickHandler.remove();
        return PostUfiButtons.getLongClickHandler(handler);
    }

    /** Stands in for modifiers of the more button that don't apply to this one (impression logging, tooltips). */
    public static Object skipModifier(Object modifier) {
        return modifier;
    }

    public static int getDownloadIconId() {
        if (downloadIconId == 0) {
            downloadIconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, UI.DRAWABLE_DOWNLOAD_ICON);
        }
        return downloadIconId;
    }

    public static String getContentDescription() {
        return str("piko_download_current_media");
    }
}
