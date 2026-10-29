//! Host demand diagnostic at explicit48/96budgets; no GPU/source changes.
use ridgeline_renderer::{mercator, selection};
fn main() {
    let elevation = std::env::args()
        .nth(1)
        .map(|v| v.parse::<f32>().expect("finite center elevation"))
        .unwrap_or(3724.);
    assert!(elevation.is_finite());
    let view = selection::View {
        width: 320,
        height: 640,
        center: mercator(170.14219572, -43.5955671),
        zoom: 11.9,
        bearing: -24.,
        pitch: 58.,
        elevation,
    };
    println!("{{\"width\":320,\"height\":640,\"elevation\":{elevation},\"geometryBudget\":48,\"imageBudget\":96,");
    for (i, (name, budget, target, max_zoom)) in [
        ("geometry", 48, 431., 22),
        ("images", 96, 256. / 2f64.powf(0.25), 19),
    ]
    .into_iter()
    .enumerate()
    {
        let ids = selection::plan_with_target(&view, budget, target, max_zoom);
        let rows: Vec<_> = ids
            .iter()
            .map(|id| format!("[{}, {}, {}]", id.z, id.x, id.y))
            .collect();
        println!("\"{name}\":[{}]{}", rows.join(","), if i == 0 { "," } else { "" });
    }
    println!("}}");
}
