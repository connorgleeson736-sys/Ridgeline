package nz.ridgeline.mobile;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Bounded path/miss memo, never tile bodies. Mutations invalidate the affected entry. */
final class TileLookup {
  private final int limit;
  private final LinkedHashMap<String, File> locations = new LinkedHashMap<>(32, .75f, true);

  TileLookup(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Invalid lookup limit");
    this.limit = limit;
  }

  synchronized InputStream open(String name, Supplier<Iterable<File>> directories)
      throws IOException {
    if (locations.containsKey(name)) {
      File file = locations.get(name);
      if (file == null) return null;
      try {
        return new FileInputStream(file);
      } catch (FileNotFoundException e) {
        // Android may remove a file, or an external restore may replace a region.
        locations.remove(name);
      }
    }
    for (File dir : directories.get()) {
      File file = new File(dir, name);
      if (file.isFile()) {
        InputStream in = new FileInputStream(file);
        remember(name, file);
        return in;
      }
    }
    remember(name, null);
    return null;
  }

  private void remember(String name, File file) {
    locations.put(name, file);
    if (locations.size() > limit) locations.remove(locations.keySet().iterator().next());
  }

  synchronized void invalidate(String name) {
    locations.remove(name);
  }

  synchronized void clear() {
    locations.clear();
  }
}
