//! dosegram-raster: one long-lived resvg 0.49.0 worker (ADR-022 addendum of 2026-11-15).
//!
//! It loads the pinned font pack once and then answers requests on standard input until the input ends.
//!
//! Request:  zoom (u32, big-endian), SVG length in bytes (u32, big-endian), the SVG as UTF-8.
//! Response: status (u8), payload length (u32, big-endian), payload. Status 0 carries a PNG; any other status
//!           carries no payload.
//!
//! The drawing is the resvg command line's, with the options `--skip-system-fonts --use-fonts-dir <fonts>
//! --resources-dir <resources> -z <zoom>`, so the decoded pixels are the same. The PNG is compressed with the fast
//! setting of the `png` crate instead of its balanced default, which costs most of the command line's time; the
//! pixels do not change. Nothing is written to standard error: a parser message could quote user text.

use std::io::{self, BufReader, BufWriter, ErrorKind, Read, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use resvg::tiny_skia;
use resvg::usvg;

/// The resvg release this worker is built from; the pixel goldens are pinned to it.
const RESVG_VERSION: &str = "0.49.0";

/// The largest request accepted: the SVG profile's documents are a few kilobytes.
const MAXIMUM_SVG_BYTES: u32 = 8 * 1024 * 1024;

/// The largest canvas drawn, as the memory cap of one render: 4096 by 4096 pixels is 64 MiB of pixmap.
const MAXIMUM_PIXELS: u64 = 4096 * 4096;

const MAXIMUM_ZOOM: u32 = 4;

const STATUS_PNG: u8 = 0;
const STATUS_BAD_REQUEST: u8 = 2;
const STATUS_NOT_SVG: u8 = 3;
const STATUS_CANVAS_TOO_LARGE: u8 = 4;
const STATUS_ENCODING_FAILED: u8 = 5;

struct Settings {
    fonts_dir: PathBuf,
    resources_dir: PathBuf,
}

fn main() -> ExitCode {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    if arguments.iter().any(|argument| argument == "--version") {
        println!("{RESVG_VERSION}");
        return ExitCode::SUCCESS;
    }
    let Some(settings) = parse(&arguments) else {
        return ExitCode::from(2);
    };
    match serve(&options(&settings)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(_) => ExitCode::from(1),
    }
}

fn parse(arguments: &[String]) -> Option<Settings> {
    let value = |name: &str| {
        arguments.iter().position(|argument| argument == name).and_then(|index| arguments.get(index + 1)).map(PathBuf::from)
    };
    Some(Settings { fonts_dir: value("--fonts-dir")?, resources_dir: value("--resources-dir")? })
}

/// The command line's options: usvg's defaults, the resources directory, and a font database with the pinned fonts
/// and the command line's generic families.
fn options(settings: &Settings) -> usvg::Options<'static> {
    let mut fonts = usvg::fontdb::Database::new();
    fonts.load_fonts_dir(&settings.fonts_dir);
    fonts.set_serif_family("Times New Roman");
    fonts.set_sans_serif_family("Arial");
    fonts.set_cursive_family("Comic Sans MS");
    fonts.set_fantasy_family("Impact");
    fonts.set_monospace_family("Courier New");
    usvg::Options { resources_dir: Some(settings.resources_dir.clone()), fontdb: Arc::new(fonts), ..usvg::Options::default() }
}

fn serve(options: &usvg::Options) -> io::Result<()> {
    let mut input = BufReader::new(io::stdin().lock());
    let mut output = BufWriter::new(io::stdout().lock());
    while let Some((zoom, length)) = header(&mut input)? {
        if length > MAXIMUM_SVG_BYTES {
            // The rest of the request is not read, so the stream cannot be resumed.
            reply(&mut output, Err(STATUS_BAD_REQUEST))?;
            return Err(io::Error::from(ErrorKind::InvalidData));
        }
        let mut svg = vec![0u8; length as usize];
        input.read_exact(&mut svg)?;
        let result = if zoom == 0 || zoom > MAXIMUM_ZOOM { Err(STATUS_BAD_REQUEST) } else { render(&svg, zoom, options) };
        reply(&mut output, result)?;
    }
    Ok(())
}

/// The next request's header, or `None` when the input ended between two requests.
fn header(input: &mut impl Read) -> io::Result<Option<(u32, u32)>> {
    let mut bytes = [0u8; 8];
    let mut filled = 0;
    while filled < bytes.len() {
        match input.read(&mut bytes[filled..]) {
            Ok(0) if filled == 0 => return Ok(None),
            Ok(0) => return Err(io::Error::from(ErrorKind::UnexpectedEof)),
            Ok(count) => filled += count,
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    let zoom = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    let length = u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    Ok(Some((zoom, length)))
}

fn reply(output: &mut impl Write, result: Result<Vec<u8>, u8>) -> io::Result<()> {
    let (status, payload) = match result {
        Ok(png) => (STATUS_PNG, png),
        Err(status) => (status, Vec::new()),
    };
    output.write_all(&[status])?;
    output.write_all(&(payload.len() as u32).to_be_bytes())?;
    output.write_all(&payload)?;
    output.flush()
}

/// The command line's path from SVG text to pixels (`resvg` 0.49.0 `main.rs`: XML parsing with DTDs allowed, the
/// usvg tree, `FitTo::Zoom`, a transparent pixmap of the ceiled size), then the PNG.
fn render(svg: &[u8], zoom: u32, options: &usvg::Options) -> Result<Vec<u8>, u8> {
    let text = std::str::from_utf8(svg).map_err(|_| STATUS_NOT_SVG)?;
    let parsing = usvg::roxmltree::ParsingOptions { allow_dtd: true, ..Default::default() };
    let document = usvg::roxmltree::Document::parse_with_options(text, parsing).map_err(|_| STATUS_NOT_SVG)?;
    let tree = usvg::Tree::from_xmltree(&document, options).map_err(|_| STATUS_NOT_SVG)?;
    let original = tree.size();
    let target = original.scale_by(zoom as f32).ok_or(STATUS_CANVAS_TOO_LARGE)?;
    let (width, height) = (target.width().ceil() as u32, target.height().ceil() as u32);
    if u64::from(width) * u64::from(height) > MAXIMUM_PIXELS {
        return Err(STATUS_CANVAS_TOO_LARGE);
    }
    let mut pixmap = tiny_skia::Pixmap::new(width, height).ok_or(STATUS_CANVAS_TOO_LARGE)?;
    let transform =
        tiny_skia::Transform::from_scale(target.width() / original.width(), target.height() / original.height());
    resvg::render(&tree, transform, &mut pixmap.as_mut());
    encode(pixmap).map_err(|_| STATUS_ENCODING_FAILED)
}

/// tiny-skia's own PNG encoding (`PixmapRef::encode_png`) with the fast compression setting.
fn encode(pixmap: tiny_skia::Pixmap) -> Result<Vec<u8>, png::EncodingError> {
    let (width, height) = (pixmap.width(), pixmap.height());
    let pixels = pixmap.take_demultiplied();
    let mut data = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut data, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.set_compression(png::Compression::Fast);
        let mut writer = encoder.write_header()?;
        writer.write_image_data(&pixels)?;
    }
    Ok(data)
}
