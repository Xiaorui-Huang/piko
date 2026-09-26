/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.entity;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.crimera.PikoUtils;

public class InstagramDialogBox{

    private Object igdsDialog;
    private Class<?> igdsClass;
    private boolean canceledOnTouchOutside;

    public InstagramDialogBox(Context context){
        try {
            igdsClass = Class.forName("className");
            Constructor<?> ctor = igdsClass.getConstructor(Context.class);
            igdsDialog = ctor.newInstance(context);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    public void addDialogMenuItems(
            CharSequence[] items,
            DialogInterface.OnClickListener listener
    ) {
        invoke(
                "A0T",
                new Class[]{DialogInterface.OnClickListener.class, CharSequence[].class},
                listener,
                items
        );
    }

    public Dialog getDialog() {
        Dialog dialog = (Dialog) invoke("A02", null);
        cancelOnTouchAroundCard(dialog);
        return dialog;
    }

    public void setCancelable(boolean value) {
        invoke("A0h", new Class[]{boolean.class}, value);
    }

    public void setCanceledOnTouchOutside(boolean value) {
        invoke("A0i", new Class[]{boolean.class}, value);
        canceledOnTouchOutside = value;
    }

    // The dialog window is wider than the visible card, so Android doesn't treat taps in the
    // side margins as outside touches. Cancel on taps that reach the window outside the card.
    private void cancelOnTouchAroundCard(Dialog dialog) {
        try {
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            ViewGroup content = window.findViewById(android.R.id.content);
            if (content == null) return;
            View decor = window.getDecorView();
            decor.setOnTouchListener((v, event) -> {
                if (!canceledOnTouchOutside || event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
                View card = findCard(content);
                if (card == null) return false;
                // Global visible rect is in window coordinates, the same as events on the decor view.
                Rect bounds = new Rect();
                card.getGlobalVisibleRect(bounds);
                if (bounds.contains((int) event.getX(), (int) event.getY())) return false;
                dialog.cancel();
                return true;
            });
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    // The card is the first view with a background below the content root, skipping plain wrappers.
    private static View findCard(ViewGroup content) {
        if (content.getChildCount() == 0) return null;
        View view = content.getChildAt(0);
        while (view.getBackground() == null && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            View onlyChild = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (onlyChild != null) return view;
                onlyChild = child;
            }
            if (onlyChild == null) return view;
            view = onlyChild;
        }
        return view;
    }

    public void setMessage(CharSequence message) {
        invoke("A0g", new Class[]{CharSequence.class}, message);
    }

    public void setNegativeButton(
            String text,
            DialogInterface.OnClickListener listener
    ) {
        invoke(
                "A0R",
                new Class[]{DialogInterface.OnClickListener.class, String.class},
                listener,
                text
        );
    }

    public void setOnDismissListener(
            DialogInterface.OnDismissListener listener
    ) {
        invoke(
                "A0U",
                new Class[]{DialogInterface.OnDismissListener.class},
                listener
        );
    }

    public void setPositiveButton(
            String text,
            DialogInterface.OnClickListener listener
    ) {
        invoke(
                "A0S",
                new Class[]{DialogInterface.OnClickListener.class, String.class},
                listener,
                text
        );
    }

    public void setTitle(String title) {
        try {
            Field f = igdsClass.getDeclaredField("A04");
            f.setAccessible(true);
            f.set(igdsDialog, title);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to setTitle", t);
        }
    }

    // ---------- reflection helper ----------

    private Object invoke(String name, Class<?>[] argsTypes, Object... args) {
        try {
            Method method = argsTypes!=null ? igdsClass.getDeclaredMethod(name, argsTypes): igdsClass.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(igdsDialog, args);

        } catch (Throwable t) {
            throw new RuntimeException("Invoke failed: " + name, t);
        }
    }
}