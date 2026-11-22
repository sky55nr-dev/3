package app.linklauncher.phone;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class IconImage {
    private static final int SIZE = 192;

    static byte[] load(ContentResolver resolver, Uri uri) throws IOException {
        Bitmap source = decode(resolver, uri);
        if (source == null) {
            throw new IOException("Unable to decode icon");
        }
        Bitmap icon = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            float scale = (float) SIZE / Math.max(source.getWidth(), source.getHeight());
            float width = source.getWidth() * scale;
            float height = source.getHeight() * scale;
            float left = (SIZE - width) / 2;
            float top = (SIZE - height) / 2;
            new Canvas(icon).drawBitmap(source, null, new RectF(left, top, left + width, top + height),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!icon.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                throw new IOException("Unable to encode icon");
            }
            return png.toByteArray();
        } finally {
            source.recycle();
            icon.recycle();
        }
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        float scale = Math.min(1f, (float) SIZE / Math.max(width, height));
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) {
            throw new IOException("Invalid image dimensions");
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > SIZE * 2) {
            options.inSampleSize *= 2;
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }
}
