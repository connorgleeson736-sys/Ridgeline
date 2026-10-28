package nz.ridgeline.mobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
  private final TileLookup lookup = new TileLookup(4096);
  private final TileMissCache misses = new TileMissCache(4096);
  private final SharedTileStore store;
  private volatile Set<String> registered = Set.of();
  private volatile boolean legacy = true;
  private final java.util.concurrent.ExecutorService migration =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ridge-map-migration");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });

  private void register() {
    Set<String> ids = new HashSet<>();
    for (var it = regions.keys(); it.hasNext(); ) ids.add(it.next());
    registered = Set.copyOf(ids);
    misses.invalidate();
  }

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
    register();
    store = new SharedTileStore(new AndroidTileDatabase(c));
    migration.execute(this::migrateLegacy);
  }

  private void migrateLegacy() {
    try {
      LegacyTileMigration.migrate(
          root,
          store,
          () -> registered,
          this,
          name -> {
            lookup.invalidate(name);
            misses.invalidate();
          },
          file -> {
            if (!file.delete() && file.exists())
              throw new IOException("Legacy map tile could not be removed");
          });
      legacy = false;
      lookup.clear();
    } catch (Exception ignored) {
      // Original files remain readable; the next process retries migration.
    } finally {
      migration.shutdown();
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
    register();
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

  public synchronized void total(String id, String generation, int total) throws Exception {
    if (!active(id, generation)) return;
    regions.getJSONObject(id).put("total", total);
    save();
  }

  private File tileFile(String id, TileAddress t) {
    return new File(new File(root, id), t.id() + ".tile");
  }

  public long size(String id, TileAddress t) {
    long size = store.size(id, t.id());
    if (size >= 0 || !legacy) return size;
    File f = tileFile(id, t);
    return f.isFile() ? f.length() : -1;
  }

  public Map<String, Long> sizes(String id) {
    Map<String, Long> sizes = store.sizes(id);
    if (legacy) {
      File[] files = new File(root, id).listFiles((d, n) -> n.endsWith(".tile"));
      if (files != null)
        for (File f : files)
          sizes.putIfAbsent(f.getName().substring(0, f.getName().length() - 5), f.length());
    }
    if (legacy) sizes.putAll(store.sizes(id));
    return sizes;
  }

  public synchronized boolean write(String id, TileAddress t, byte[] data) throws Exception {
    if (!active(id)) return false;
    try {
      store.write(id, Map.of(t.id(), data));
    } finally {
      misses.invalidate();
    }
    lookup.invalidate(t.id() + ".tile");
    return true;
  }

  public synchronized boolean writeBatch(
      String id, Map<TileAddress, byte[]> data, String generation) {
    if (!active(id, generation)) return false;
    Map<String, byte[]> rows = new LinkedHashMap<>();
    data.forEach((t, b) -> rows.put(t.id(), b));
    try {
      store.write(id, rows);
    } finally {
      misses.invalidate();
    }
    data.keySet().forEach(t -> lookup.invalidate(t.id() + ".tile"));
    return true;
  }

  public long sizeAny(TileAddress tile) {
    long size = store.sizeAny(registered, tile.id());
    if (size >= 0 || !legacy) return size;
    for (String id : registered) {
      File f = tileFile(id, tile);
      if (f.isFile()) return f.length();
    }
    return -1;
  }

  public synchronized boolean write(String id, TileAddress t, byte[] data, String generation)
      throws Exception {
    return active(id, generation) && write(id, t, data);
  }

  public byte[] read(TileAddress t) throws IOException {
    Set<String> ids = registered;
    if (ids.isEmpty()) return null;
    long observed = misses.epoch();
    if (misses.contains(t.id())) return null;
    boolean hadLegacy = legacy;
    byte[] bytes = store.readAny(ids, t.id());
    if (bytes != null) return bytes;
    if (!hadLegacy) {
      misses.remember(t.id(), observed);
      return null;
    }
    InputStream opened;
    synchronized (this) {
      opened =
          lookup.open(
              t.id() + ".tile",
              () -> {
                ArrayList<File> dirs = new ArrayList<>(regions.length());
                for (var it = regions.keys(); it.hasNext(); ) dirs.add(new File(root, it.next()));
                return dirs;
              });
    }
    // A descriptor opened under the region lock remains readable if its pathname
    // is removed on Android. Independent tile body reads no longer serialize.
    try (InputStream in = opened) {
      byte[] result =
          in == null ? store.readAny(registered, t.id()) : TileNetwork.read(in, TileNetwork.MAX);
      if (result == null) misses.remember(t.id(), observed);
      return result;
    }
  }

  public byte[] read(String id, TileAddress t) throws IOException {
    if (!registered.contains(id)) throw new IOException("The region no longer exists.");
    boolean hadLegacy = legacy;
    byte[] bytes = store.read(id, t.id());
    if (bytes != null || !hadLegacy) return bytes;
    InputStream opened;
    synchronized (this) {
      if (!regions.has(id)) throw new IOException("The region no longer exists.");
      File f = tileFile(id, t);
      if (!f.isFile()) return store.read(id, t.id());
      opened = new FileInputStream(f);
    }
    try (InputStream in = opened) {
      return TileNetwork.read(in, TileNetwork.MAX);
    }
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
    register();
    store.remove(id);
    lookup.clear();
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
