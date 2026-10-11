//  HighlightItem.swift
//  NOOP · Highlights — one card ready to draw: its category row, its sentence, its figures and how those
//  figures are written, and where it files on the Highlights pages.

import SwiftUI
import StrandDesign

/// What a set of highlights is about; one row on the All Highlights page.
struct HighlightSubject: Identifiable, Hashable {
    enum Kind: Hashable {
        case metric(key: String, source: String)
        case workouts
        case heartRate
    }
    let kind: Kind
    let title: String
    let category: HealthCategory

    var id: String {
        switch kind {
        case .metric(let key, let source): return "metric:\(source):\(key)"
        case .workouts: return "workouts"
        case .heartRate: return "heartRate"
        }
    }
}

struct HighlightItem: Identifiable {
    let id: String
    let subject: HighlightSubject
    let cadence: HighlightCadence
    let icon: String
    let title: String
    let tint: Color
    let sentence: String
    let body: HighlightBody
    /// How this card writes one of its figures ("1 189 kcal", "7 hr 30 min").
    let figure: (Double) -> [MetricHealthStyle.Token]
    /// The words over a week's average ("Average Steps").
    var weekLabel = ""
    /// The page the card opens from the Summary.
    var route: TabRoute?
}

/// One subject's highlights, filed by how often each is renewed.
struct HighlightGroup: Identifiable {
    let subject: HighlightSubject
    let items: [HighlightItem]

    var id: String { subject.id }

    func items(_ cadence: HighlightCadence) -> [HighlightItem] { items.filter { $0.cadence == cadence } }
}

extension HighlightCadence {
    var sectionTitle: LocalizedStringKey {
        switch self {
        case .daily: return "Daily Highlights"
        case .weekly: return "Weekly Highlights"
        case .monthly: return "Monthly Highlights"
        case .yearly: return "Yearly Highlights"
        }
    }
}
