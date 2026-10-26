package nz.ridgeline.mobile;

import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(RidgelineOfflinePlugin.class);
    super.onCreate(savedInstanceState);
    bridge.getWebView().setWebViewClient(new OfflineWebViewClient(bridge));
    View parent = (View) bridge.getWebView().getParent();
    ViewCompat.setOnApplyWindowInsetsListener(
        parent,
        (view, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars()
                      | WindowInsetsCompat.Type.displayCutout()
                      | WindowInsetsCompat.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(parent);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                bridge
                    .getWebView()
                    .evaluateJavascript(
                        "!!globalThis.RidgeMobile?.back()",
                        result -> {
                          if (!"true".equals(result)) moveTaskToBack(true);
                        });
              }
            });
  }
}
