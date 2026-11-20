package nz.ridgeline.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** One application-lifetime listener, rather than two binder queries per tile. */
final class TileConnectivity {
  private static TileConnectivity instance;
  private final ConnectivityManager manager;
  private final DefaultNetworkState state = new DefaultNetworkState();
  private final boolean monitored;

  static synchronized TileConnectivity get(Context context) {
    if (instance == null) instance = new TileConnectivity(context.getApplicationContext());
    return instance;
  }

  private TileConnectivity(Context context) {
    manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    boolean registered = false;
    if (manager != null) {
      try {
        Network current = manager.getActiveNetwork();
        state.available(current);
        state.capabilities(current, internet(manager.getNetworkCapabilities(current)));
        manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
          @Override public void onAvailable(Network network) { state.available(network); }
          @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            state.capabilities(network, internet(caps));
          }
          @Override public void onLost(Network network) { state.lost(network); }
        });
        registered = true;
      } catch (RuntimeException unavailable) {
        // Preserve the original direct-query path if callback registration fails.
      }
    }
    monitored = registered;
  }

  private static boolean internet(NetworkCapabilities caps) {
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  boolean connected() {
    if (monitored) return state.connected();
    return manager != null && internet(manager.getNetworkCapabilities(manager.getActiveNetwork()));
  }
}
