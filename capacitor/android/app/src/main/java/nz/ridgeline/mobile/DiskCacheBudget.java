package nz.ridgeline.mobile;

/** One disposable allowance. Downloaded regions and imported overviews are separate. */
final class DiskCacheBudget {
  static final int DEFAULT_MB = 256;
  static int normalize(int mb) { return Math.max(0, Math.min(1000, mb)); }
  static long total(int mb, long free) {
    return Math.min(normalize(mb) * 1_000_000L, Math.max(4_000_000L, Math.max(0, free) / 8));
  }
}
