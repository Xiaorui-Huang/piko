/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Tap and long press behaviour shared by the feed, Reels and Story download buttons:
 * tap downloads the current media, long press shows the download options.
 */
@SuppressWarnings("unused")
public final class DownloadButtonActions {
    private DownloadButtonActions() {}

    // Tap handler that also carries the long press handler for the same media.
    private static final class ClickHandler implements Function1<Object, Unit> {
        final Function1<Object, Boolean> longClick;
        private final Function1<Object, Unit> click;

        ClickHandler(Function1<Object, Unit> click, Function1<Object, Boolean> longClick) {
            this.click = click;
            this.longClick = longClick;
        }

        @Override
        public Unit invoke(Object event) {
            return click.invoke(event);
        }
    }

    // Dialogs need the activity the clicked view belongs to.
    static Context getActivity(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? context : Utils.getActivity();
    }

    // Click events of component buttons wrap the clicked view.
    static View getView(Object event) {
        try {
            for (Field field : event.getClass().getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                View view = (View) field.get(event);
                if (view != null) return view;
            }
        } catch (Exception e) {
            Logger.printException(() -> "DownloadButtonActions getView failure", e);
        }
        return null;
    }

    private static Context getContext(Object event) {
        View view = getView(event);
        return view != null ? getActivity(view) : Utils.getActivity();
    }

    // Custom buttons don't vibrate on their own, so tap and long press buzz like the native buttons.
    static void haptic(View view, int feedback) {
        if (view != null) view.performHapticFeedback(feedback);
    }

    static void tap(View view, UserSession userSession, Object media, int position) {
        haptic(view, HapticFeedbackConstants.VIRTUAL_KEY);
        DownloadUtils.downloadCurrentMedia(getActivity(view), userSession, media, position);
    }

    static void longPress(View view, UserSession userSession, Object media, int position) {
        haptic(view, HapticFeedbackConstants.LONG_PRESS);
        DownloadUtils.showDownloadMenu(getActivity(view), userSession, media, position);
    }

    // Component click handler for a single media (index 0).
    static Function1<Object, Unit> createClickHandler(UserSession userSession, Object media) {
        return new ClickHandler(
                event -> {
                    haptic(getView(event), HapticFeedbackConstants.VIRTUAL_KEY);
                    DownloadUtils.downloadCurrentMedia(getContext(event), userSession, media, 0);
                    return Unit.INSTANCE;
                },
                event -> {
                    haptic(getView(event), HapticFeedbackConstants.LONG_PRESS);
                    DownloadUtils.showDownloadMenu(getContext(event), userSession, media, 0);
                    return Boolean.TRUE;
                }
        );
    }

    public static Function1<Object, Boolean> getLongClickHandler(Function1<Object, Unit> clickHandler) {
        return ((ClickHandler) clickHandler).longClick;
    }
}
