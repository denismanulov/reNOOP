//  ActivityRingsView.swift
//  NOOP · Summary home — three concentric rings drawn the way Apple Watch draws Activity.
//
//  Drawing only. Each ring gets a fill fraction already clamped by `RingFraction` and a start → end hue
//  pair: a dark track in its own hue, then an arc whose colour deepens-to-brightens along its length, with
//  round caps painted in the exact hue at each end (an angular gradient alone would paint the start cap
//  in the END colour, since the cap reaches back past 0°). The rings sit on a black disc, as Health's
//  small Activity rings do in either appearance — the neon hues are made for black.

import SwiftUI
import StrandDesign

struct ActivityRing: Identifiable {
    let id: String
    let fraction: Double
    let start: Color
    let end: Color
    /// Fitness's black glyph riding each ring's start; nil draws none.
    var glyph: ActivityRingGlyph? = nil
}

/// Activity's three ring glyphs — Move →, Exercise ⇉, Stand ↑ — as round-capped strokes. The points are
/// measured from the final frames of ActivityRingsUI's `ringIconSprite` (iOS 26.5, 172 px cells), so the
/// weight and the angle of the heads are Apple's. Apple does not scale the glyph with the ring: on the
/// single wide iPhone ring its cell is 0.82 of the ring's width, on the three narrower rings it is the full
/// width with a slightly heavier stroke — the size used here.
enum ActivityRingGlyph {
    case move, exercise, stand

    static let cellToLineWidth: CGFloat = 1.0

    fileprivate var strokes: [[CGPoint]] {
        switch self {
        case .move:
            return [[CGPoint(x: 44, y: 86), CGPoint(x: 130, y: 86)],
                    [CGPoint(x: 84.5, y: 35), CGPoint(x: 130, y: 86), CGPoint(x: 84.5, y: 137)]]
        case .exercise:
            return [[CGPoint(x: 27, y: 86), CGPoint(x: 102, y: 86)],
                    [CGPoint(x: 62, y: 41), CGPoint(x: 102, y: 86), CGPoint(x: 62, y: 131)],
                    [CGPoint(x: 105, y: 41), CGPoint(x: 145, y: 86), CGPoint(x: 105, y: 131)]]
        case .stand:
            return [[CGPoint(x: 86, y: 36), CGPoint(x: 86, y: 138)],
                    [CGPoint(x: 34, y: 83), CGPoint(x: 86, y: 36), CGPoint(x: 138, y: 83)]]
        }
    }
}

/// One ring glyph drawn in a square `side` points across.
private struct RingGlyphView: View {
    let glyph: ActivityRingGlyph
    let side: CGFloat

    var body: some View {
        let scale = side / 172
        Path { path in
            for stroke in glyph.strokes {
                path.addLines(stroke.map { CGPoint(x: $0.x * scale, y: $0.y * scale) })
            }
        }
        .stroke(Color.black, style: StrokeStyle(lineWidth: 16 * scale, lineCap: .round, lineJoin: .miter))
        .frame(width: side, height: side)
    }
}

struct ActivityRingsView: View {
    /// Outermost first.
    let rings: [ActivityRing]
    var diameter: CGFloat = 132
    /// Fitness's Summary proportions: wide rings nearly touching, straight on the card. `disc` still sets
    /// them on black, as the light appearance needs (the neon hues are made for black).
    var fitness = false
    var disc = true
    /// A ring's width and the gap between rings in points, where a screen is drawn to Fitness's own
    /// measure of that ring size (its Sharing tab sets each size differently); nil keeps the proportions.
    var stroke: CGFloat? = nil
    var gap: CGFloat? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    /// The disc's margin around the outer ring.
    private var inset: CGFloat { disc ? diameter * 0.04 : 0 }
    private var lineWidth: CGFloat { stroke ?? diameter * (fitness ? 0.112 : 0.1) }
    private var ringGap: CGFloat { gap ?? diameter * (fitness ? 0.006 : 0.01) }

    var body: some View {
        ZStack {
            if disc { Circle().fill(Color.black) }
            ForEach(Array(rings.enumerated()), id: \.element.id) { index, ring in
                let step = inset + CGFloat(index) * (lineWidth + ringGap)
                ringView(ring, size: diameter - step * 2)
            }
        }
        .frame(width: diameter, height: diameter)
        .onAppear {
            if reduceMotion { appeared = true } else {
                withAnimation(.spring(response: 0.9, dampingFraction: 0.85)) { appeared = true }
            }
        }
        .accessibilityHidden(true)
    }

    private func ringView(_ ring: ActivityRing, size: CGFloat) -> some View {
        RingArc(fraction: appeared ? ring.fraction : 0, start: ring.start, end: ring.end, lineWidth: lineWidth,
                glyph: ring.glyph)
            .frame(width: size, height: size)
    }
}

/// One ring. Animatable over its fraction so the fill, the gradient and the end cap all travel along the
/// arc together while the rings fill in (a plain offset would carry the cap across the chord).
private struct RingArc: View, Animatable {
    var fraction: Double
    let start: Color
    let end: Color
    let lineWidth: CGFloat
    var glyph: ActivityRingGlyph? = nil

    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    var body: some View {
        GeometryReader { geo in
            // The stroke straddles its path, so the path is inset by half the width: the ring then sits
            // fully inside its frame and its centre line is exactly `radius`, where the caps ride.
            let radius = (min(geo.size.width, geo.size.height) - lineWidth) / 2
            ZStack {
                Circle()
                    .stroke(start.opacity(0.26), lineWidth: lineWidth)
                    .padding(lineWidth / 2)
                if fraction > 0.001 {
                    Circle()
                        .trim(from: 0, to: fraction)
                        .stroke(AngularGradient(gradient: Gradient(colors: [start, end]), center: .center,
                                                startAngle: .zero, endAngle: .degrees(360 * fraction)),
                                style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                        .padding(lineWidth / 2)
                        .rotationEffect(.degrees(-90))
                    cap(start, radius: radius, angle: 0)
                    // The leading end casts a soft shadow ahead of itself, onto the ring it is about to
                    // cover, as Activity draws it: none falls back on the arc it ends.
                    cap(.black.opacity(0.45), radius: radius, angle: 360 * fraction + shadowLead(radius))
                        .blur(radius: lineWidth * 0.12)
                    cap(end, radius: radius, angle: 360 * fraction)
                }
                if let glyph {
                    RingGlyphView(glyph: glyph, side: lineWidth * ActivityRingGlyph.cellToLineWidth)
                        .offset(y: -radius)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    /// How far ahead of the leading end its shadow sits, in degrees: a little more than the shadow's own
    /// blur, so the blur does not reach back past the end.
    private func shadowLead(_ radius: CGFloat) -> Double {
        guard radius > 0 else { return 0 }
        return Double(lineWidth * 0.16 / radius) * 180 / .pi
    }

    /// A round cap centred on the ring's path at `angle` degrees clockwise from twelve o'clock.
    private func cap(_ color: Color, radius: CGFloat, angle: Double) -> some View {
        let rad = (angle - 90) * .pi / 180
        return Circle()
            .fill(color)
            .frame(width: lineWidth, height: lineWidth)
            .offset(x: radius * CGFloat(cos(rad)), y: radius * CGFloat(sin(rad)))
    }
}
