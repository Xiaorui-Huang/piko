/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import android.content.Context;
import android.view.HapticFeedbackConstants;

import com.instagram.common.session.UserSession;

import kotlin.jvm.functions.Function1;

/**
 * Long press handler of the Litho feed download button; shows the download options.
 * Takes the same arguments as {@link FeedDownloadClickFunction}.
 */
public final class FeedDownloadLongClickFunction implements Function1<Object, Object> {
    private final Context context;
    private final UserSession userSession;
    private final Object mediaSource;

    public FeedDownloadLongClickFunction(Context context, UserSession userSession, Object mediaSource) {
        this.context = context;
        this.userSession = userSession;
        this.mediaSource = mediaSource;
    }

    @Override
    public Object invoke(Object clickEvent) {
        DownloadButtonActions.haptic(DownloadButtonActions.getView(clickEvent), HapticFeedbackConstants.LONG_PRESS);
        DownloadUtils.showDownloadMenu(
                context,
                userSession,
                DownloadUtils.extractMedia(mediaSource),
                DownloadUtils.currentMediaIndex(mediaSource));
        return Boolean.TRUE;
    }
}
