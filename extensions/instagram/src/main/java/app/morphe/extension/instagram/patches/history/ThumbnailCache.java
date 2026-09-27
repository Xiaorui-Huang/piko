/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.history;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.db.PikoHistoryDb;
import app.morphe.extension.shared.Logger;

/**
 * Thumbnails for view history. Each one is downloaded once when the item is logged, while its
 * signed CDN URL is still valid (they expire after a few days), and saved as a small WebP file.
 * The history screen reads that file and only falls back to the URL when it's missing.
 */
final class ThumbnailCache {

    /** Saved width in px. A grid card is half the screen wide; ~14 KB per thumbnail. */
    static final int WIDTH = 360;
    private static final int QUALITY = 70;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    private ThumbnailCache() {
    }

    /** Saves the thumbnail of a newly logged item, unless it's already saved. */
    static void save(String mediaPk, String url) {
        if (mediaPk == null || url == null || url.isEmpty()) return;
        EXECUTOR.execute(() -> {
            try {
                File file = PikoHistoryDb.getInstance(PikoUtils.getContext()).thumbnailFile(mediaPk);
                if (!file.exists()) downloadAndSave(url, file);
            } catch (Exception e) {
                Logger.printInfo(() -> "Thumbnail save failed: " + url, e);
            }
        });
    }

    static void load(ImageView imageView, String mediaPk, String url) {
        imageView.setTag(mediaPk);
        imageView.setImageDrawable(null);
        if (mediaPk == null) return;

        Bitmap cached = CACHE.get(mediaPk);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = PikoHistoryDb.getInstance(imageView.getContext()).thumbnailFile(mediaPk);
                if (file.exists()) {
                    bitmap = BitmapFactory.decodeFile(file.getPath());
                } else if (url != null && !url.isEmpty()) {
                    bitmap = downloadAndSave(url, file);
                }
            } catch (Exception e) {
                Logger.printInfo(() -> "Thumbnail load failed: " + url, e);
            }
            if (bitmap == null) return;

            CACHE.put(mediaPk, bitmap);
            Bitmap result = bitmap;
            MAIN_HANDLER.post(() -> {
                if (mediaPk.equals(imageView.getTag())) {
                    imageView.setImageBitmap(result);
                }
            });
        });
    }

    private static Bitmap downloadAndSave(String url, File file) throws Exception {
        Bitmap bitmap = decode(download(url));
        if (bitmap == null) return null;

        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        // Write to a temp file of its own first, so an interrupted write or a concurrent save and
        // load of the same item never leave a truncated thumbnail.
        File temp = File.createTempFile(file.getName(), ".tmp", dir);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            bitmap.compress(webp(), QUALITY, output);
        }
        if (!temp.renameTo(file)) temp.delete();
        return bitmap;
    }

    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat webp() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** Decodes the image scaled down to {@link #WIDTH} wide (never up). */
    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        int sampleSize = 1;
        while (options.outWidth / (sampleSize * 2) >= WIDTH) {
            sampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null || bitmap.getWidth() <= WIDTH) return bitmap;

        int height = Math.round(bitmap.getHeight() * (float) WIDTH / bitmap.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, WIDTH, height, true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }
}
