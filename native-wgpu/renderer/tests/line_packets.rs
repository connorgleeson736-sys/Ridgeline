//! Line packets built by the Android app's own Kotlin code from real LINZ
//! vector tiles (LinePacketExportTest writes them to LINE_OUT) must all parse
//! in the renderer: one rejected packet freezes every overlay in the app.
use ridgeline_renderer::overlay::{parse_line_packet, GroupInput};

#[test]
fn kotlin_packets_parse() {
    let Ok(dir) = std::env::var("LINE_OUT") else {
        eprintln!("LINE_OUT not set; skipped");
        return;
    };
    let mut files: Vec<_> = std::fs::read_dir(&dir).unwrap().filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "bin")).collect();
    files.sort();
    assert!(!files.is_empty());
    let mut rejected = Vec::new();
    let mut lines = 0;
    for path in &files {
        let bytes = std::fs::read(path).unwrap();
        match parse_line_packet(&bytes) {
            Some(groups) => lines += groups.iter().map(|g| match g { GroupInput::New(g) => g.lines.len(), _ => 0 }).sum::<usize>(),
            None => rejected.push(format!("{} ({} bytes)", path.display(), bytes.len())),
        }
    }
    eprintln!("{} packets, {lines} lines, {} rejected", files.len(), rejected.len());
    assert!(rejected.is_empty(), "rejected packets:\n{}", rejected.join("\n"));
}
