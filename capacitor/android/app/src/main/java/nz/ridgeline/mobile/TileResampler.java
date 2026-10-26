package nz.ridgeline.mobile;

import android.graphics.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Enlarge saved ancestor detail without inventing elevation or changing RGB encoding. */
final class TileResampler {
  static byte[] parent(TileArchive archive, TileAddress request) throws IOException {
    if (!request.raster() || request.z < 1) return null;
    for (int z = request.z - 1; z >= 0; z--) {
      int shift = request.z - z;
      TileAddress p = TileAddress.tile(request.kind, z, request.x >> shift, request.y >> shift);
      byte[] bytes = archive.read(p);
      if (bytes != null) return crop(bytes, request, shift);
    }
    return null;
  }

  static byte[] crop(byte[] data, TileAddress tile, int shift) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inScaled = false;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap input = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (input == null) throw new IOException("Saved map image could not be decoded.");
    Bitmap output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    try {
      double scale = Math.scalb(1.0, -shift),
          left = (tile.x & ((1 << shift) - 1)) * 256 * scale,
          top = (tile.y & ((1 << shift) - 1)) * 256 * scale;
      if (tile.kind.equals("elevation") || tile.kind.equals("mapzen")) {
        int[] pixels = new int[65536], result = new int[65536];
        input.getPixels(pixels, 0, 256, 0, 0, 256, 256);
        for (int y = 0; y < 256; y++)
          for (int x = 0; x < 256; x++) {
            double px = Math.max(0, Math.min(255, left + (x + .5) * scale - .5)),
                py = Math.max(0, Math.min(255, top + (y + .5) * scale - .5));
            int ix = (int) px,
                iy = (int) py,
                nx = Math.min(255, ix + 1),
                ny = Math.min(255, iy + 1);
            double fx = px - ix, fy = py - iy;
            if ((pixels[iy * 256 + ix] >>> 24) == 0
                || (pixels[iy * 256 + nx] >>> 24) == 0
                || (pixels[ny * 256 + ix] >>> 24) == 0
                || (pixels[ny * 256 + nx] >>> 24) == 0) {
              result[y * 256 + x] = 0;
              continue;
            }
            double height =
                (pixels[iy * 256 + ix] & 0xffffff) * (1 - fx) * (1 - fy)
                    + (pixels[iy * 256 + nx] & 0xffffff) * fx * (1 - fy)
                    + (pixels[ny * 256 + ix] & 0xffffff) * (1 - fx) * fy
                    + (pixels[ny * 256 + nx] & 0xffffff) * fx * fy;
            // Both Terrain RGB and Terrarium are linear encodings of
            // the 24-bit integer. Interpolate the integer, never channels.
            result[y * 256 + x] =
                0xff000000 | Math.max(0, Math.min(0xffffff, (int) Math.round(height)));
          }
        output.setPixels(result, 0, 256, 0, 0, 256, 256);
      } else {
        Canvas canvas = new Canvas(output);
        Matrix matrix = new Matrix();
        matrix.setRectToRect(
            new RectF(
                (float) left,
                (float) top,
                (float) (left + 256 * scale),
                (float) (top + 256 * scale)),
            new RectF(0, 0, 256, 256),
            Matrix.ScaleToFit.FILL);
        canvas.drawBitmap(input, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      output.compress(Bitmap.CompressFormat.PNG, 100, out);
      return out.toByteArray();
    } finally {
      input.recycle();
      output.recycle();
    }
  }
}
