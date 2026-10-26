package nz.ridgeline.mobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** User downloads live in private files, separate from evictable WebView caches. */
public final class TileArchive {
  private static TileArchive instance;

  public static synchronized TileArchive get(Context context) {
    if (instance == null) instance = new TileArchive(context.getApplicationContext());
    return instance;
  }

  private final Context context;
  private final File root;
  private final AtomicFile index;
  private JSONObject regions;

  private TileArchive(Context c) {
    context = c;
    root = new File(c.getFilesDir(), "offline-regions");
    root.mkdirs();
    index = new AtomicFile(new File(root, "index.json"));
    try (InputStream in = index.openRead()) {
      regions =
          new JSONObject(new String(TileNetwork.read(in, TileNetwork.MAX), StandardCharsets.UTF_8));
    } catch (FileNotFoundException e) {
      regions = new JSONObject();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Offline region index could not be read. Existing downloads were retained.", e);
    }
  }

  private void save() throws Exception {
    FileOutputStream out = null;
    try {
      out = index.startWrite();
      out.write(regions.toString().getBytes(StandardCharsets.UTF_8));
      index.finishWrite(out);
    } catch (Exception e) {
      if (out != null) index.failWrite(out);
      throw e;
    }
  }

  public synchronized String create(JSONObject config, TilePlan plan) throws Exception {
    if (regions.length() >= 30)
      throw new IOException(
          "Remove an old offline region before adding another (30 region limit).");
    String id = UUID.randomUUID().toString();
    JSONObject r = new JSONObject(config.toString());
    r.put("id", id)
        .put("total", plan.tiles.size())
        .put("done", 0)
        .put("bytes", 0)
        .put("state", "queued")
        .put("error", "");
    regions.put(id, r);
    try {
      save();
    } catch (Exception e) {
      regions.remove(id);
      throw e;
    }
    return id;
  }

  public synchronized JSONObject region(String id) throws Exception {
    JSONObject r = regions.optJSONObject(id);
    if (r == null) throw new IOException("The offline region no longer exists.");
    return new JSONObject(r.toString());
  }

  public synchronized JSONArray list() throws Exception {
    JSONArray result = new JSONArray();
    for (var it = regions.keys(); it.hasNext(); ) {
      JSONObject copy = region(it.next());
      copy.remove("key");
      result.put(copy);
    }
    return result;
  }

  public synchronized boolean active(String id) {
    JSONObject r = regions.optJSONObject(id);
    return r != null && "downloading".equals(r.optString("state"));
  }

  public synchronized String queue(String id) throws Exception {
    JSONObject r = regions.optJSONObject(id);
    if (r == null) throw new IOException("The region no longer exists.");
    String generation = UUID.randomUUID().toString();
    r.put("generation", generation).put("state", "queued").put("error", "");
    save();
    return generation;
  }

  public synchronized boolean active(String id, String generation) {
    JSONObject r = regions.optJSONObject(id);
    return active(id) && generation.equals(r.optString("generation"));
  }

  public synchronized boolean begin(String id, String generation) throws Exception {
    JSONObject r = regions.optJSONObject(id);
    if (r == null
        || !generation.equals(r.optString("generation"))
        || !(r.optString("state").equals("queued") || r.optString("state").equals("downloading")))
      return false;
    r.put("state", "downloading");
    save();
    return true;
  }

  public synchronized void finish(String id, String generation, String state, String error)
      throws Exception {
    if (active(id, generation)) state(id, state, error);
  }

  public synchronized boolean exists(String id) {
    return regions.has(id);
  }

  public synchronized void state(String id, String state, String error) throws Exception {
    JSONObject r = regions.optJSONObject(id);
    if (r == null) return;
    r.put("state", state).put("error", error);
    save();
  }

  public synchronized void progress(String id, String generation, int done, long bytes)
      throws Exception {
    if (active(id, generation)) progress(id, done, bytes);
  }

  public synchronized void progress(String id, int done, long bytes) throws Exception {
    JSONObject r = regions.optJSONObject(id);
    if (r == null) return;
    r.put("done", done).put("bytes", bytes);
    save();
  }

  private File tileFile(String id, TileAddress t) {
    return new File(new File(root, id), t.id() + ".tile");
  }

  public synchronized long size(String id, TileAddress t) {
    File f = tileFile(id, t);
    return f.isFile() ? f.length() : -1;
  }

  public synchronized boolean write(String id, TileAddress t, byte[] data) throws Exception {
    // Pause/removal and the final rename share this lock: an in-flight HTTP
    // response can never recreate a deleted download directory.
    if (!active(id)) return false;
    File f = tileFile(id, t);
    if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs())
      throw new IOException("Offline storage is unavailable.");
    AtomicFile file = new AtomicFile(f);
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(data);
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
    return true;
  }

  public synchronized boolean write(String id, TileAddress t, byte[] data, String generation)
      throws Exception {
    return active(id, generation) && write(id, t, data);
  }

  public synchronized byte[] read(TileAddress t) throws IOException {
    for (var it = regions.keys(); it.hasNext(); ) {
      File f = tileFile(it.next(), t);
      if (f.isFile())
        try (InputStream in = new FileInputStream(f)) {
          return TileNetwork.read(in, TileNetwork.MAX);
        }
    }
    return null;
  }

  public synchronized void remove(String id) throws Exception {
    if (!regions.has(id)) return;
    JSONObject old = regions.getJSONObject(id);
    regions.remove(id);
    try {
      save();
    } catch (Exception e) {
      regions.put(id, old);
      throw e;
    }
    delete(new File(root, id));
  }

  private void delete(File file) throws IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    if (file.exists() && !file.delete())
      throw new IOException("Some offline files could not be removed.");
  }

  public boolean offlineOnly() {
    return context
        .getSharedPreferences("mobile", Context.MODE_PRIVATE)
        .getBoolean("offlineOnly", false);
  }

  public void offlineOnly(boolean value) throws IOException {
    if (!context
        .getSharedPreferences("mobile", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("offlineOnly", value)
        .commit()) throw new IOException("The offline setting could not be saved.");
  }
}
