package nz.ridgeline.mobile;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;

/**
 * Android-backed adapter; common SQL and ownership logic are exercised with real SQLite in JVM
 * tests.
 */
final class AndroidTileDatabase extends SQLiteOpenHelper implements SharedTileStore.Database {
  AndroidTileDatabase(Context context) {
    super(
        context,
        "saved-tiles-v1.db",
        null,
        1,
        db -> {
          throw new SQLiteDatabaseCorruptException(
              "Saved-map database could not be read. Existing files were retained.");
        });
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
    db.execSQL("PRAGMA synchronous=FULL");
  }

  @Override
  public void onCreate(SQLiteDatabase db) {}

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    throw new IllegalStateException("Unsupported saved-map database version");
  }

  @Override
  public void initializeStorage() {
    // Both commands must use the same persistent primary writer connection.
    SQLiteDatabase db = getWritableDatabase();
    db.execSQL("PRAGMA auto_vacuum=FULL");
    db.execSQL("VACUUM");
  }

  @Override
  public void exec(String sql, Object... args) {
    if (args.length == 0) getWritableDatabase().execSQL(sql);
    else getWritableDatabase().execSQL(sql, args);
  }

  @Override
  public SharedTileStore.Rows query(String sql, String... args) {
    Cursor c = getReadableDatabase().rawQuery(sql, args);
    return new SharedTileStore.Rows() {
      public boolean next() {
        return c.moveToNext();
      }

      public String string(int col) {
        return c.getString(col);
      }

      public long number(int col) {
        return c.getLong(col);
      }

      public byte[] bytes(int col) {
        return c.getBlob(col);
      }

      public void close() {
        c.close();
      }
    };
  }

  @Override
  public void transaction(Runnable work) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransactionNonExclusive();
    try {
      work.run();
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }
}
