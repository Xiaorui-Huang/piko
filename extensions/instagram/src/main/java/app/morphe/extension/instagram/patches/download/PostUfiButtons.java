/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Adds buttons next to save in the feed post UFI row (like/comment/share/save).
 * The row is a component tree, so new buttons are copies of the save icon component
 * with a different drawable and click handler.
 */
@SuppressWarnings("unused")
public class PostUfiButtons {
    // Constructor arguments of the save icon component of the row currently being built.
    private static final ThreadLocal<Object[]> saveIcon = new ThreadLocal<>();
    // Long press handler created together with the last click handler on this thread.
    private static final ThreadLocal<Function1<Object, Boolean>> longClickHandler = new ThreadLocal<>();

    private static Field currentIndexField;
    private static Constructor<?> iconConstructor;
    private static int downloadIconId;

    // Replaced at patch time with the obfuscated carousel index field name.
    private static String getCurrentIndexFieldName() {
        return "A08";
    }

    private static int getCurrentIndex(Object carouselState) {
        try {
            if (carouselState == null) return 0;
            if (currentIndexField == null) {
                Field field = carouselState.getClass().getDeclaredField(getCurrentIndexFieldName());
                field.setAccessible(true);
                currentIndexField = field;
            }
            return currentIndexField.getInt(carouselState);
        } catch (Exception e) {
            Logger.printException(() -> "PostUfiButtons getCurrentIndex failure", e);
            return 0;
        }
    }

    // Click events wrap the clicked view; dialogs need the activity it belongs to.
    private static Context getContext(Object event) {
        try {
            for (Field field : event.getClass().getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                View view = (View) field.get(event);
                if (view == null) continue;
                Context context = view.getContext();
                while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                    context = ((ContextWrapper) context).getBaseContext();
                }
                if (context instanceof Activity) return context;
            }
        } catch (Exception e) {
            Logger.printException(() -> "PostUfiButtons getContext failure", e);
        }
        return Utils.getActivity();
    }

    public static void captureSaveIcon(Object component, ImageView.ScaleType scaleType, Object modifier,
                                       Integer tint, int drawable, int color) {
        saveIcon.set(new Object[]{component, scaleType, tint, color});
    }

    public static Function1<Object, Unit> getClickHandler(UserSession userSession, Object media, Object carouselState) {
        if (media == null || !Pref.enableDownload() || !Pref.postDownloadButton()) return null;
        longClickHandler.set(event -> {
            DownloadUtils.showDownloadMenu(getContext(event), userSession, media, getCurrentIndex(carouselState));
            return Boolean.TRUE;
        });
        return event -> {
            DownloadUtils.downloadCurrentMedia(getContext(event), userSession, media, getCurrentIndex(carouselState));
            return Unit.INSTANCE;
        };
    }

    public static Function1<Object, Boolean> takeLongClickHandler() {
        Function1<Object, Boolean> handler = longClickHandler.get();
        longClickHandler.remove();
        return handler;
    }

    public static void addDownloadButton(ArrayList<Object> icons, Object modifier) {
        Object[] save = saveIcon.get();
        saveIcon.remove();
        if (save == null) return;
        try {
            if (iconConstructor == null) {
                for (Constructor<?> constructor : save[0].getClass().getDeclaredConstructors()) {
                    if (constructor.getParameterTypes().length == 5) iconConstructor = constructor;
                }
                downloadIconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, UI.DRAWABLE_DOWNLOAD_ICON);
            }
            icons.add(iconConstructor.newInstance(save[1], modifier, save[2], downloadIconId, save[3]));
        } catch (Exception e) {
            PikoUtils.logger(e);
            Logger.printException(() -> "PostUfiButtons addDownloadButton failure", e);
        }
    }
}
