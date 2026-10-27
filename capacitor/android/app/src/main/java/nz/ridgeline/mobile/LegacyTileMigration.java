package nz.ridgeline.mobile;

import java.io.*;
import java.util.*;
import java.util.function.*;

/** Bounded, idempotent migration. Originals survive each batch until a durable checkpoint. */
final class LegacyTileMigration {
  interface Delete {
    void remove(File file) throws IOException;
  }

  static void migrate(
      File root,
      SharedTileStore store,
      Supplier<Set<String>> regions,
      Object lock,
      Consumer<String> invalidate,
      Delete delete)
      throws IOException {
    synchronized (lock) {
      store.retain(regions.get());
    }
    for (String id : regions.get()) {
      File dir = new File(root, id);
      File[] files = dir.listFiles((d, n) -> n.endsWith(".tile"));
      if (files == null) continue;
      Map<String, byte[]> batch = new LinkedHashMap<>();
      List<File> originals = new ArrayList<>();
      long bytes = 0;
      for (File file : files) {
        if (!regions.get().contains(id)) break;
        String name = file.getName(), key = name.substring(0, name.length() - 5);
        originals.add(file);
        if (store.size(id, key) < 0) {
          byte[] data;
          try (InputStream in = new FileInputStream(file)) {
            data = TileNetwork.read(in, TileNetwork.MAX);
          } catch (FileNotFoundException gone) {
            originals.remove(file);
            continue;
          }
          batch.put(key, data);
          bytes += data.length;
        }
        if (originals.size() >= 16 || bytes >= 4 * 1024 * 1024) {
          flush(id, store, regions, lock, batch, originals, invalidate, delete);
          batch.clear();
          originals.clear();
          bytes = 0;
        }
      }
      if (!originals.isEmpty())
        flush(id, store, regions, lock, batch, originals, invalidate, delete);
      File[] left = dir.listFiles();
      if (left != null && left.length == 0) dir.delete();
    }
    store.releaseJournal();
  }

  private static void flush(
      String id,
      SharedTileStore store,
      Supplier<Set<String>> regions,
      Object lock,
      Map<String, byte[]> batch,
      List<File> originals,
      Consumer<String> invalidate,
      Delete delete)
      throws IOException {
    synchronized (lock) {
      if (!regions.get().contains(id)) return;
      if (!batch.isEmpty()) {
        long needed = batch.values().stream().mapToLong(b -> b.length).sum();
        File dir = originals.get(0).getParentFile();
        if (dir.getUsableSpace() < 32L * 1024 * 1024 + needed)
          throw new IOException("Original map files retained until more storage is available");
        store.write(id, batch);
      }
    }
    store.checkpoint();
    synchronized (lock) {
      if (!regions.get().contains(id)) return;
      for (File file : originals) {
        String n = file.getName();
        if (store.size(id, n.substring(0, n.length() - 5)) < 0) continue;
        delete.remove(file);
        invalidate.accept(n);
      }
    }
  }
}
