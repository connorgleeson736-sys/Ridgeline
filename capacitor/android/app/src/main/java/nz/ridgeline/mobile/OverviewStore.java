package nz.ridgeline.mobile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.AtomicFile;
import com.getcapacitor.JSObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class OverviewStore {
  private static OverviewStore instance;

  static synchronized OverviewStore get(Context context) throws Exception {
    if (instance == null) instance = new OverviewStore(context.getApplicationContext());
    return instance;
  }

  private final Object importLock = new Object();
  private final File root;
  private final Map<String, Long> files = new LinkedHashMap<>();
  private final Set<String> allowedRanges = new HashSet<>();
  private final AssetManager assets;
  private final Set<String> bundled = new HashSet<>();

  private OverviewStore(Context context) throws Exception {
    assets = context.getAssets();
    root = new File(context.getFilesDir(), "precomputed-overviews");
    JSONObject manifest;
    try (InputStream in = context.getAssets().open("public/overview-manifest.json")) {
      manifest =
          new JSONObject(
              new String(
                  TileNetwork.read(in, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
    }
    JSONArray list = manifest.getJSONArray("files");
    for (int i = 0; i < list.length(); i++) {
      JSONObject f = list.getJSONObject(i);
      String sha = f.getString("sha256");
      long bytes = f.getLong("bytes");
      if (!sha.matches("[a-f0-9]{64}") || bytes <= 0 || bytes > 64L * 1024 * 1024)
        throw new IOException("Invalid built-in overview manifest.");
      files.put(sha, bytes);
      if (manifest.optBoolean("bundled", false)) {
        try (AssetFileDescriptor asset = assets.openFd("public/overview-packs/" + sha + ".bin")) {
          if (asset.getLength() != bytes)
            throw new IOException("Bundled overview size does not match its manifest.");
          bundled.add(sha);
        }
      }
    }
    JSONArray ranges = manifest.getJSONArray("ranges");
    for (int i = 0; i < ranges.length(); i++) {
      JSONArray r = ranges.getJSONArray(i);
      String hash = r.getString(0);
      long start = r.getLong(1), size = r.getLong(2);
      if (!files.containsKey(hash)
          || start < 0
          || size < 20
          || size > 1024 * 1024
          || start + size > files.get(hash)) throw new IOException("Invalid overview image range.");
      allowedRanges.add(hash + "/" + start + "/" + size);
    }
  }

  synchronized JSObject status() {
    int done = 0;
    long bytes = 0, total = 0;
    long importedBytes = 0;
    for (var f : files.entrySet()) {
      total += f.getValue();
      File file = new File(root, f.getKey() + ".bin");
      boolean imported = file.isFile() && file.length() == f.getValue();
      if (imported) importedBytes += file.length();
      if (bundled.contains(f.getKey()) || imported) {
        done++;
        bytes += f.getValue();
      }
    }
    JSObject result = new JSObject();
    result.put("done", done);
    result.put("total", files.size());
    result.put("bytes", bytes);
    result.put("totalBytes", total);
    result.put("complete", done == files.size() && !files.isEmpty());
    result.put("bundled", bundled.size() == files.size() && !files.isEmpty());
    result.put("importedBytes", importedBytes);
    return result;
  }

  interface Progress {
    void changed(JSObject value);
  }

  int importZip(InputStream input, Progress progress) throws Exception {
    synchronized (importLock) {
      return importLocked(input, progress);
    }
  }

  private int importLocked(InputStream input, Progress progress) throws Exception {
    if (!root.isDirectory() && !root.mkdirs())
      throw new IOException("Offline storage is unavailable.");
    int imported = 0, entries = 0;
    long ignored = 0;
    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input, 32768))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > 20000) throw new IOException("Too many files in the overview ZIP.");
        String name = entry.getName().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String sha = name.endsWith(".bin") ? name.substring(0, name.length() - 4) : "";
        if (entry.isDirectory() || !files.containsKey(sha)) {
          byte[] skip = new byte[8192];
          int n;
          while ((n = zip.read(skip)) != -1) {
            ignored += n;
            if (ignored > 16L * 1024 * 1024)
              throw new IOException(
                  "Choose the prepared overview ZIPs, rather than the website source or an"
                      + " unrelated archive.");
          }
          continue;
        }
        long expected = files.get(sha);
        if (root.getUsableSpace() < expected + 32L * 1024 * 1024)
          throw new IOException("Not enough free storage to import this overview pack.");
        File destination = new File(root, sha + ".bin");
        if (destination.isFile() && destination.length() == expected) {
          OverviewIntegrity.copy(
              zip,
              new OutputStream() {
                @Override
                public void write(int b) {}

                @Override
                public void write(byte[] b, int off, int len) {}
              },
              expected,
              sha);
          imported++;
          progress.changed(status());
          continue;
        }
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream out = null;
        try {
          out = file.startWrite();
          OverviewIntegrity.copy(zip, out, expected, sha);
          file.finishWrite(out);
        } catch (Exception e) {
          if (out != null) file.failWrite(out);
          throw e;
        }
        imported++;
        progress.changed(status());
      }
    }
    return imported;
  }

  record Image(InputStream data, int length) {}

  Image open(String path) throws Exception {
    String prefix = "/_ridge/overviews/";
    if (!path.startsWith(prefix)) return null;
    String key = path.substring(prefix.length());
    if (!allowedRanges.contains(key)) throw new IOException("Invalid overview image address.");
    String[] parts = key.split("/");
    long offset = Long.parseLong(parts[1]);
    int length = Integer.parseInt(parts[2]);
    if (bundled.contains(parts[0])) {
      AssetFileDescriptor asset = assets.openFd("public/overview-packs/" + parts[0] + ".bin");
      FileInputStream in = null;
      try {
        in = asset.createInputStream();
        return new Image(
            OverviewRangeReader.open(
                in, asset.getStartOffset(), asset.getLength(), offset, length, asset),
            length);
      } catch (Exception e) {
        if (in != null) in.close();
        asset.close();
        throw e;
      }
    }
    File file = new File(root, parts[0] + ".bin");
    if (!file.isFile() || file.length() != files.get(parts[0])) return null;
    FileInputStream in = new FileInputStream(file);
    try {
      return new Image(
          OverviewRangeReader.open(in, 0, file.length(), offset, length, () -> {}), length);
    } catch (Exception e) {
      in.close();
      throw e;
    }
  }

  void remove() throws IOException {
    synchronized (importLock) {
      for (String sha : files.keySet()) {
        AtomicFile file = new AtomicFile(new File(root, sha + ".bin"));
        file.delete();
        if (file.getBaseFile().exists())
          throw new IOException("An overview file could not be removed.");
      }
    }
  }
}
