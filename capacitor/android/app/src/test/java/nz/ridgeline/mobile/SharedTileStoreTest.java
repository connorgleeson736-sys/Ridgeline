package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SharedTileStoreTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  static final class Jdbc implements SharedTileStore.Database {
    final String url;
    final ThreadLocal<Connection> transaction = new ThreadLocal<>();
    volatile boolean failCheckpoint, failInsert;

    Jdbc(File file) throws Exception {
      Class.forName("org.sqlite.JDBC");
      url = "jdbc:sqlite:" + file;
      try (Connection c = open();
          Statement s = c.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
      }
    }

    Connection open() throws SQLException {
      Connection c = DriverManager.getConnection(url);
      try (Statement s = c.createStatement()) {
        s.execute("PRAGMA foreign_keys=ON");
        s.execute("PRAGMA busy_timeout=5000");
        s.execute("PRAGMA synchronous=FULL");
      }
      return c;
    }

    @Override
    public void initializeStorage() {
      try (Connection c = open();
          Statement s = c.createStatement()) {
        s.execute("PRAGMA auto_vacuum=FULL");
        s.execute("VACUUM");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void exec(String sql, Object... args) {
      if (failInsert && sql.startsWith("INSERT OR REPLACE"))
        throw new IllegalStateException("Simulated interrupted transaction");
      Connection c = transaction.get();
      boolean close = c == null;
      try {
        if (c == null) c = open();
        try (PreparedStatement s = c.prepareStatement(sql)) {
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          s.execute();
        }
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      } finally {
        if (close && c != null)
          try {
            c.close();
          } catch (SQLException ignored) {
          }
      }
    }

    @Override
    public SharedTileStore.Rows query(String sql, String... args) {
      if (failCheckpoint && sql.contains("wal_checkpoint"))
        throw new IllegalStateException("Simulated busy checkpoint");
      try {
        Connection c = open();
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
        ResultSet r = s.executeQuery();
        return new SharedTileStore.Rows() {
          public boolean next() {
            try {
              return r.next();
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }

          public String string(int n) {
            try {
              return r.getString(n + 1);
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }

          public long number(int n) {
            try {
              return r.getLong(n + 1);
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }

          public byte[] bytes(int n) {
            try {
              return r.getBytes(n + 1);
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }

          public void close() {
            try {
              r.close();
              s.close();
              c.close();
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }
        };
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void transaction(Runnable work) {
      try (Connection c = open()) {
        c.setAutoCommit(false);
        transaction.set(c);
        try {
          work.run();
          c.commit();
        } catch (RuntimeException e) {
          c.rollback();
          throw e;
        } finally {
          transaction.remove();
        }
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  SharedTileStore store;
  Jdbc db;

  @Before
  public void setup() throws Exception {
    db = new Jdbc(new File(temp.newFolder(), "tiles.db"));
    store = new SharedTileStore(db);
  }

  @Test
  public void identicalBytesShareStorageButRegionOwnershipRemainsSeparate() throws Exception {
    byte[] tile = {1, 2, 3};
    store.write("a", Map.of("aerial_3_1_2", tile));
    store.write("b", Map.of("aerial_3_1_2", tile));
    assertEquals(3, store.physicalBytes());
    store.remove("a");
    assertNull(store.read("a", "aerial_3_1_2"));
    assertArrayEquals(tile, store.read("b", "aerial_3_1_2"));
    store.remove("b");
    assertEquals(0, store.physicalBytes());
  }

  @Test
  public void differentVersionsOfTheSameCoordinateStayByteExact() throws Exception {
    byte[] old = {1, 2, 3}, newer = {4, 5, 6};
    store.write("old", Map.of("topo_18_1_2", old));
    store.write("new", Map.of("topo_18_1_2", newer));
    assertEquals(6, store.physicalBytes());
    assertArrayEquals(old, store.read("old", "topo_18_1_2"));
    assertArrayEquals(newer, store.read("new", "topo_18_1_2"));
    assertNull(store.readAny(Set.of("unrelated"), "topo_18_1_2"));
  }

  @Test
  public void transactionFailureCannotLeaveHalfAnOwnerOrPayload() throws Exception {
    db.failInsert = true;
    assertThrows(
        IllegalStateException.class, () -> store.write("a", Map.of("tile", new byte[] {1})));
    db.failInsert = false;
    assertEquals(0, store.physicalBytes());
    assertNull(store.read("a", "tile"));
  }

  @Test
  public void indexQueriesReturnLengthsWithoutReadingBodies() {
    store.write("a", Map.of("one", new byte[123], "two", new byte[321]));
    assertEquals(123, store.size("a", "one"));
    assertEquals(321, store.sizeAny(Set.of("a"), "two"));
    assertEquals(-1, store.sizeAny(Set.of("b"), "two"));
    assertEquals(2, store.sizes("a").size());
  }

  @Test
  public void restartRetainsPayloadsAndCleansOnlyUnregisteredOwners() throws Exception {
    store.write("keep", Map.of("tile", new byte[] {7}));
    store.write("removed", Map.of("tile2", new byte[] {8}));
    store = new SharedTileStore(db);
    store.retain(Set.of("keep"));
    assertArrayEquals(new byte[] {7}, store.read("keep", "tile"));
    assertNull(store.read("removed", "tile2"));
    assertEquals(1, store.physicalBytes());
  }

  @Test
  public void walAllowsReadingWhileAnotherRegionWrites() throws Exception {
    store.write("a", Map.of("tile", new byte[] {7}));
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int worker = 0; worker < 3; worker++) {
        final int w = worker;
        futures.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 20; i++) {
                    if (w == 0) store.write("b", Map.of("tile" + i, new byte[] {(byte) i}));
                    else
                      try {
                        assertArrayEquals(new byte[] {7}, store.read("a", "tile"));
                      } catch (IOException e) {
                        throw new AssertionError(e);
                      }
                  }
                }));
      }
      for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void legacyFilesSurviveAnInterruptedCheckpointAndMigrateOnRetry() throws Exception {
    File root = temp.newFolder(), dir = new File(root, "a");
    assertTrue(dir.mkdir());
    File tile = new File(dir, "aerial_3_1_2.tile");
    byte[] bytes = {0, 1, 2, 3};
    Files.write(tile.toPath(), bytes);
    db.failCheckpoint = true;
    assertThrows(
        IllegalStateException.class,
        () ->
            LegacyTileMigration.migrate(
                root, store, () -> Set.of("a"), this, n -> {}, f -> Files.delete(f.toPath())));
    assertArrayEquals(bytes, Files.readAllBytes(tile.toPath()));
    assertArrayEquals(bytes, store.read("a", "aerial_3_1_2"));
    db.failCheckpoint = false;
    LegacyTileMigration.migrate(
        root, store, () -> Set.of("a"), this, n -> {}, f -> Files.delete(f.toPath()));
    assertFalse(tile.exists());
    assertArrayEquals(bytes, store.read("a", "aerial_3_1_2"));
  }

  @Test
  public void retryAfterInterruptedLegacyCleanupIsIdempotent() throws Exception {
    File root = temp.newFolder(), dir = new File(root, "a");
    assertTrue(dir.mkdir());
    for (int i = 0; i < 5; i++)
      Files.write(new File(dir, "tile" + i + ".tile").toPath(), new byte[] {(byte) i});
    AtomicInteger deleted = new AtomicInteger();
    assertThrows(
        IOException.class,
        () ->
            LegacyTileMigration.migrate(
                root,
                store,
                () -> Set.of("a"),
                this,
                n -> {},
                f -> {
                  if (deleted.incrementAndGet() == 2) throw new IOException("Process interrupted");
                  Files.delete(f.toPath());
                }));
    LegacyTileMigration.migrate(
        root, store, () -> Set.of("a"), this, n -> {}, f -> Files.delete(f.toPath()));
    assertEquals(5, store.sizes("a").size());
    for (int i = 0; i < 5; i++)
      assertArrayEquals(new byte[] {(byte) i}, store.read("a", "tile" + i));
    assertFalse(dir.exists());
  }

  @Test
  public void deletionDuringMigrationCannotResurrectARegion() throws Exception {
    File root = temp.newFolder(), dir = new File(root, "a");
    assertTrue(dir.mkdir());
    Files.write(new File(dir, "tile.tile").toPath(), new byte[] {1});
    AtomicInteger calls = new AtomicInteger();
    LegacyTileMigration.migrate(
        root,
        store,
        () -> calls.incrementAndGet() < 4 ? Set.of("a") : Set.of(),
        this,
        n -> {},
        f -> Files.delete(f.toPath()));
    assertNull(store.read("a", "tile"));
  }

  @Test
  public void removingTheLastOwnerReclaimsTheDatabasePayloadSpace() throws Exception {
    store.write("a", Map.of("large", new byte[2 * 1024 * 1024]));
    store.checkpoint();
    File file = new File(db.url.substring("jdbc:sqlite:".length()));
    long before = file.length();
    store.remove("a");
    assertTrue(file.length() < before / 2);
    assertEquals(0, store.physicalBytes());
  }

  @Test
  public void largeTilesUseBoundedSqlRowsAndKeepTheirExactBytes() throws Exception {
    byte[] bytes = new byte[3 * 1024 * 1024];
    new Random(42).nextBytes(bytes);
    store.write("a", Map.of("large", bytes));
    assertArrayEquals(bytes, store.read("a", "large"));
    assertArrayEquals(bytes, store.readAny(Set.of("a"), "large"));
  }
}
