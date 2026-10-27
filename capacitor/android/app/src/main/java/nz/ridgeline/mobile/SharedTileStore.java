package nz.ridgeline.mobile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Content-addressed SQLite payloads with separate, transactional region ownership. */
final class SharedTileStore {
  interface Rows extends AutoCloseable {
    boolean next();

    String string(int col);

    long number(int col);

    byte[] bytes(int col);

    @Override
    void close();
  }

  interface Database {
    void initializeStorage();

    void exec(String sql, Object... args);

    Rows query(String sql, String... args);

    void transaction(Runnable work);
  }

  private final Database db;

  SharedTileStore(Database db) {
    this.db = db;
    boolean fresh;
    try (Rows r =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='tile_blobs'")) {
      fresh = !r.next();
    }
    if (fresh) {
      db.initializeStorage();
    }
    db.exec(
        "CREATE TABLE IF NOT EXISTS tile_blobs(hash TEXT PRIMARY KEY,data BLOB NOT NULL,bytes"
            + " INTEGER NOT NULL)");
    db.exec(
        "CREATE TABLE IF NOT EXISTS region_tiles(region_id TEXT NOT NULL,tile_id TEXT NOT NULL,hash"
            + " TEXT NOT NULL REFERENCES tile_blobs(hash),PRIMARY KEY(region_id,tile_id))");
    db.exec("CREATE INDEX IF NOT EXISTS tile_coordinate ON region_tiles(tile_id,region_id)");
    db.exec("CREATE INDEX IF NOT EXISTS tile_content ON region_tiles(hash)");
  }

  static String hash(byte[] data) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data);
      char[] hex = "0123456789abcdef".toCharArray(), out = new char[64];
      for (int i = 0; i < bytes.length; i++) {
        out[i * 2] = hex[(bytes[i] >>> 4) & 15];
        out[i * 2 + 1] = hex[bytes[i] & 15];
      }
      return new String(out);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  void write(String region, Map<String, byte[]> tiles) {
    // Hash outside the database transaction to minimise the writer lock.
    Map<String, String> hashes = new HashMap<>();
    tiles.forEach((id, data) -> hashes.put(id, hash(data)));
    db.transaction(
        () ->
            tiles.forEach(
                (id, data) -> {
                  String sha = hashes.get(id);
                  db.exec(
                      "INSERT OR IGNORE INTO tile_blobs(hash,data,bytes) VALUES(?,?,?)",
                      sha,
                      data,
                      data.length);
                  db.exec(
                      "INSERT OR REPLACE INTO region_tiles(region_id,tile_id,hash) VALUES(?,?,?)",
                      region,
                      id,
                      sha);
                }));
    // Pin durability to a completed checkpoint even if an older Android
    // connection pool reopens its writer with the platform WAL sync default.
    checkpoint();
  }

  byte[] read(String region, String tile) throws IOException {
    try (Rows rows =
        db.query(
            "SELECT b.hash,b.bytes,CASE WHEN b.bytes<=1048576 THEN b.data ELSE NULL END FROM"
                + " region_tiles r JOIN tile_blobs b ON b.hash=r.hash WHERE r.region_id=? AND"
                + " r.tile_id=?",
            region,
            tile)) {
      return body(rows);
    }
  }

  byte[] readAny(Set<String> regions, String tile) throws IOException {
    if (regions.isEmpty()) return null;
    String[] args = args(tile, regions);
    try (Rows rows =
        db.query(
            "SELECT b.hash,b.bytes,CASE WHEN b.bytes<=1048576 THEN b.data ELSE NULL END FROM"
                + " region_tiles r JOIN tile_blobs b ON b.hash=r.hash WHERE r.tile_id=? AND"
                + " r.region_id IN("
                + placeholders(regions.size())
                + ") LIMIT 1",
            args)) {
      return body(rows);
    }
  }

  private byte[] body(Rows rows) throws IOException {
    if (!rows.next()) return null;
    long size = rows.number(1);
    if (size < 0 || size > TileNetwork.MAX) throw new IOException("Invalid stored tile size");
    if (size <= 1048576) {
      byte[] bytes = rows.bytes(2);
      if (bytes.length != size) throw new IOException("Stored map tile is incomplete");
      return bytes;
    }
    // Keep large rows below Android's CursorWindow limit, including Android 8.
    String hash = rows.string(0);
    byte[] bytes = new byte[(int) size];
    for (int offset = 0; offset < bytes.length; ) {
      int count = Math.min(262144, bytes.length - offset);
      try (Rows part =
          db.query(
              "SELECT substr(data,?,?) FROM tile_blobs WHERE hash=?",
              Integer.toString(offset + 1),
              Integer.toString(count),
              hash)) {
        if (!part.next()) throw new IOException("Saved map data is no longer available");
        byte[] chunk = part.bytes(0);
        if (chunk.length != count) throw new IOException("Stored map tile is incomplete");
        System.arraycopy(chunk, 0, bytes, offset, count);
        offset += count;
      }
    }
    if (!hash.equals(hash(bytes))) throw new IOException("Saved map tile integrity check failed");
    return bytes;
  }

  long size(String region, String tile) {
    try (Rows rows =
        db.query(
            "SELECT b.bytes FROM region_tiles r JOIN tile_blobs b ON b.hash=r.hash WHERE"
                + " r.region_id=? AND r.tile_id=?",
            region,
            tile)) {
      return rows.next() ? rows.number(0) : -1;
    }
  }

  long sizeAny(Set<String> regions, String tile) {
    if (regions.isEmpty()) return -1;
    try (Rows rows =
        db.query(
            "SELECT b.bytes FROM region_tiles r JOIN tile_blobs b ON b.hash=r.hash WHERE"
                + " r.tile_id=? AND r.region_id IN("
                + placeholders(regions.size())
                + ") LIMIT 1",
            args(tile, regions))) {
      return rows.next() ? rows.number(0) : -1;
    }
  }

  Map<String, Long> sizes(String region) {
    Map<String, Long> result = new HashMap<>();
    try (Rows rows =
        db.query(
            "SELECT r.tile_id,b.bytes FROM region_tiles r JOIN tile_blobs b ON b.hash=r.hash WHERE"
                + " r.region_id=?",
            region)) {
      while (rows.next()) result.put(rows.string(0), rows.number(1));
    }
    return result;
  }

  void remove(String region) {
    db.transaction(
        () -> {
          db.exec("DELETE FROM region_tiles WHERE region_id=?", region);
          collect();
        });
    releaseJournal();
  }

  void retain(Set<String> regions) {
    db.transaction(
        () -> {
          if (regions.isEmpty()) db.exec("DELETE FROM region_tiles");
          else
            db.exec(
                "DELETE FROM region_tiles WHERE region_id NOT IN("
                    + placeholders(regions.size())
                    + ")",
                regions.toArray());
          collect();
        });
  }

  private void collect() {
    db.exec(
        "DELETE FROM tile_blobs WHERE NOT EXISTS(SELECT 1 FROM region_tiles WHERE"
            + " region_tiles.hash=tile_blobs.hash)");
  }

  long physicalBytes() {
    try (Rows r = db.query("SELECT COALESCE(SUM(bytes),0) FROM tile_blobs")) {
      return r.next() ? r.number(0) : 0;
    }
  }

  void checkpoint() {
    try (Rows r = db.query("PRAGMA wal_checkpoint(FULL)")) {
      if (r.next() && r.number(0) != 0)
        throw new IllegalStateException("Saved-map checkpoint is busy; original files retained");
    }
  }

  void releaseJournal() {
    try (Rows r = db.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
      while (r.next()) {}
    } catch (RuntimeException ignored) {
    }
  }

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }

  private static String[] args(String tile, Set<String> regions) {
    List<String> args = new ArrayList<>();
    args.add(tile);
    args.addAll(regions);
    return args.toArray(new String[0]);
  }
}
