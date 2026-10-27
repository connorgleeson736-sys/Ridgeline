// One-time migration: retain app data, but stop carrying APK rendering choices
// into the website's Direct rendering policy. Later user adjustments remain saved.
export function resetWebsiteRenderPreferences(storage){
 const marker='ridgeline-website-render-defaults-v1';
 if(storage.getItem(marker)==='1')return;
 for(const key of ['ridgeline-phone-mode-v1','ridgeline-phone-detail-v1','ridgeline-detail-v1','ridgeline-speed-detail-v1','ridgeline-adaptive-terrain-v1','ridgeline-aggressive-coverage-v1','ridgeline-prepare-ahead-v1','ridgeline-expanded-angle-v1','ridgeline-imagery-fade-v1','ridgeline-loading-v1','ridgeline-filtering-v1','ridgeline-sky-v1'])storage.removeItem(key);
 storage.setItem(marker,'1');
}
