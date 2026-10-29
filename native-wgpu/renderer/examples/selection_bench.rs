//! Cloud CPU workload only. Does not predict phone FPS or download completion.
use ridgeline_renderer::{
    mercator,
    selection::{View, plan},
};
fn main() {
    let mut times = Vec::new();
    let mut counts = Vec::new();
    for i in 0..600 {
        let view = View {
            width: 1080,
            height: 1920,
            center: mercator(170.14219572 + (i % 50) as f64 * 0.0001, -43.5955671),
            zoom: 13.3 + (i % 20) as f64 * 0.02,
            bearing: -24. + (i % 80) as f32,
            pitch: 58.,
            elevation: 3724.,
        };
        let start = std::time::Instant::now();
        let ids = std::hint::black_box(plan(&view, 96));
        if i >= 100 {
            times.push(start.elapsed().as_secs_f64() * 1e6);
            counts.push(ids.len());
        }
    }
    times.sort_by(f64::total_cmp);
    counts.sort();
    println!(
        "{{\"scope\":\"warmed cloud CPU / native quadtree selection only, not phone speed\",\"samples\":{},\"medianUs\":{},\"p95Us\":{},\"minTiles\":{},\"maxTiles\":{},\"budget\":96}}",
        times.len(),
        times[times.len() / 2],
        times[times.len() * 95 / 100],
        counts[0],
        counts[counts.len() - 1]
    );
}
