//! Line packets built by the Android app's own Kotlin code from real LINZ
//! vector tiles (LinePacketExportTest, LINE_OUT) parse in the renderer: the
//! full packet and one referencing groups the renderer holds.
use ridgeline_renderer::overlay::{parse_line_packet, GroupInput};

#[test]
fn kotlin_packets_parse() {
    let Ok(dir) = std::env::var("LINE_OUT") else {
        eprintln!("LINE_OUT not set; skipped");
        return;
    };
    let meta = std::fs::read_to_string(format!("{dir}/meta.txt")).unwrap();
    let groups: usize = meta.split(' ').next().unwrap().parse().unwrap();
    let full = std::fs::read(format!("{dir}/full.bin")).unwrap();
    let parsed = parse_line_packet(&full).expect("full packet parses");
    assert_eq!(parsed.len(), groups);
    let lines: usize = parsed.iter().map(|g| match g { GroupInput::New(g) => g.lines.len(), _ => 0 }).sum();
    assert!(lines > 0, "lines present");
    let refs = std::fs::read(format!("{dir}/refs.bin")).unwrap();
    let parsed = parse_line_packet(&refs).expect("reference packet parses");
    assert_eq!(parsed.len(), groups);
    let existing = parsed.iter().filter(|g| matches!(g, GroupInput::Existing(..))).count();
    assert_eq!(existing, groups / 2);
    eprintln!("parsed {groups} groups, {lines} lines; {existing} by reference");
}
