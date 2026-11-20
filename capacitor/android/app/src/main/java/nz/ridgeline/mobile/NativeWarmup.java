package nz.ridgeline.mobile;

import android.content.Context;

/**
 * Startup work that would otherwise run on the first intercepted tile request:
 * opening the offline index/SQLite store, reading the HTTP cache journal and
 * the DNS/TCP/TLS setup for each provider. It overlaps with WebView and
 * renderer start-up instead of delaying the first visible tiles.
 */
final class NativeWarmup {
  static final String[] ORIGINS = {
    "https://basemaps.linz.govt.nz/", "https://services.arcgisonline.com/"
  };

  private NativeWarmup() {}

  static void start(Context context) {
    Context app = context.getApplicationContext();
    Thread thread =
        new Thread(
            () -> {
              try {
                TileConnectivity connectivity = TileConnectivity.get(app);
                TileArchive archive = TileArchive.get(app);
                try {
                  OverviewStore.get(app);
                } catch (Exception ignored) {
                  // Overview reads report their own failure on use.
                }
                TileHttpClient client = TileNetwork.client(app);
                client.warm(
                    archive.offlineOnly() || !connectivity.connected()
                        ? new String[0]
                        : ORIGINS);
              } catch (RuntimeException ignored) {
                // Every component is still created lazily by the first request.
              }
            },
            "ridge-warmup");
    thread.setDaemon(true);
    thread.start();
  }
}
