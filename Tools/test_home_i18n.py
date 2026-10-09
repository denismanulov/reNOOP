#!/usr/bin/env python3
"""Focused zero-baseline localization guard for the phone Home/Today surfaces.

This intentionally does not use the repository-wide grandfathered baseline.  Once the
Home migration is complete, every finding in this source closure must stay at zero.
Run with::

    python3 Tools/test_home_i18n.py
"""

from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import i18n_audit as audit


ROOT = Path(__file__).resolve().parents[1]

# The Android Home (the Summary tab, which replaced the old Today screen) plus the Today-era helpers
# it still reads. The helpers only the old Today screen used (day navigation, section layout, the "Your
# cards" registry, the auto-workout nudge) were deleted with it, and so were the assertions that pinned
# their strings. AppRoot is deliberately handled separately below: most of that file is unrelated
# navigation UI, while only its Summary tab resource belongs here.
ANDROID_SUMMARY_FILES = {
    "android/app/src/main/java/com/noop/ui/summary/SummaryScreen.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryCards.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryCompact.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryEditSheet.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryLogic.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryLoader.kt",
    "android/app/src/main/java/com/noop/ui/summary/SummaryPrefs.kt",
}
ANDROID_HOME_FILES = ANDROID_SUMMARY_FILES | {
    "android/app/src/main/java/com/noop/ui/TodayMetricsLogic.kt",
    "android/app/src/main/java/com/noop/ui/TodayProvenance.kt",
    "android/app/src/main/java/com/noop/ui/TodayScoring.kt",
    "android/app/src/main/java/com/noop/ui/KeyMetricPrefs.kt",
    "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt",
    "android/app/src/main/java/com/noop/analytics/StepsEstimateEngine.kt",
    "android/app/src/main/java/com/noop/analytics/RecoveryDrivers.kt",
}
ANDROID_SHELL_FILE = "android/app/src/main/java/com/noop/ui/AppRoot.kt"

# The Today customization editor/metadata, the cards and banners Home renders, and the iPhone
# shell/icon actions that enter Home.
APPLE_HOME_FILES = {
    "Strand/Screens/TodayCustomizationSheet.swift",
    "Strand/Screens/TodayCustomizationMetadata.swift",
    "StrandiOS/System/HomeScreenQuickActions.swift",
    "Strand/Screens/HealthAlertBanner.swift",
}
APPLE_SHELL_FILE = "StrandiOS/App/RootTabView.swift"

# RootTabView also owns the unrelated More tab.  Limit its Home contract to the
# Today tab label, the sheet opened from Today's + button, and that sheet's close
# affordance instead of treating every future RootTabView string as Home copy.
APPLE_HOME_SHELL_CATALOG_KEYS = {
    "Today", "Done", "QUICK ACTIONS", "Live HR", "Start workout", "Log journal", "Breathe",
}

# These helpers return display strings through variables, which scan_android cannot
# data-flow back from Text(variable).  Scan every literal in them conservatively.  The
# allowlist contains only persistence/source identifiers and date formats, never copy.
ANDROID_DISPLAY_HELPERS = {
    "android/app/src/main/java/com/noop/ui/TodayMetricsLogic.kt",
    "android/app/src/main/java/com/noop/ui/TodayProvenance.kt",
    "android/app/src/main/java/com/noop/ui/TodayScoring.kt",
}

# These producers feed strings into Home through fields/lambdas, so the general Android scanner cannot
# see the eventual Text(variable). Only stable wire ids, preference keys, units and format specs belong
# in the allowlist; every human phrase must become a semantic/resource contract.
ANDROID_INDIRECT_COPY_FILES = {
    "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt",
    "android/app/src/main/java/com/noop/analytics/StepsEstimateEngine.kt",
    "android/app/src/main/java/com/noop/ui/KeyMetricPrefs.kt",
    "android/app/src/main/java/com/noop/analytics/RecoveryDrivers.kt",
}
ANDROID_INDIRECT_NON_UI_LITERALS = {
    # Engine keys, metric units and numeric format specs.
    "hrv", "rhr", "respRate", "acwr", "monotony", "ms", "bpm", "rpm", "%.1f",
    # Stable KeyMetric raw values + preferences.
    "charge", "effort", "rest", "restingHr", "bloodOxygen", "respiratory", "steps",
    "weight", "calories", "skinTemp", "today.keyMetrics", ",", "",
}

# Confirmed runtime copy that reaches Today through variables, lambdas, model fields or accessibility
# aggregation. The normal Compose scanner cannot follow those producer paths, so pin every confirmed
# phrase until it has a resource/semantic contract. Wire ids and format strings are intentionally absent.
ANDROID_TODAY_RUNTIME_COPY = {
    "Today", "Yesterday", "day", "days",
    "Session running", "Session ended", "Start session",
    "Guarding — silence means you're on track.", "See the summary of your last session.",
    "Strap-guided effort session. It only buzzes when you drift off today's band.", "BETA",
    "Building your baseline", "Charge, Effort and Rest become personal after a few nights of wear.",
    "Live now. Your scores are building.", "Charge, Effort and Rest build over your next few nights of wear.",
    "Cards with no value yet show a dash.", "SOLID", "BUILDING", "CALIBRATING",
    "5-minute average | selected day", "5-minute average | since midnight",
    "24-hour heart rate", "Still", "Walking", "Running",
    "Good morning", "Good afternoon", "Good evening", "No Data", "Calibrating",
    "7-day trend", "14-day trend", "30-day trend", "1 week", "2 weeks", "1 month",
}
ANDROID_HELPER_NON_UI_LITERALS = {
    # TodayScoring parse/default formats.
    "9999-12-31",
    # TodayProvenance source ids and metric dictionary keys.
    "-noop", "recovery", "strain", "sleep_performance", "oura-import", "oura-api",
    "fitbit-import", "garmin-import", "xiaomi-band",
}

# AppRoot's Home-owned shell string is resource-backed: the visible Summary bottom-tab label (the
# old Today "+" quick-actions sheet went with Today).  Do not gate unrelated AppRoot findings.
ANDROID_HOME_SHELL_RESOURCES = {"nav_summary"}

R_STRING = re.compile(r"\bR\.string\.([A-Za-z_][A-Za-z0-9_]*)")
SWIFT_LOCALIZED_CALL = re.compile(r"\b(?:String\s*\(\s*localized:|LocalizedStringKey\s*\()\s*\"")


def _format_findings(rows: list[tuple[str, int, str]]) -> str:
    return "\n".join(f"{path}:{line}: {literal!r}" for path, line, literal in rows)


def _all_kotlin_literals(path: Path) -> list[tuple[int, str]]:
    """All non-comment string literals, using the audit's template-aware lexer."""
    text = audit._mask_comments(path.read_text(encoding="utf-8"))
    result: list[tuple[int, str]] = []
    i = 0
    while i < len(text):
        if text[i] != '"':
            i += 1
            continue
        end = audit._skip_string_literal(text, i)
        result.append((text.count("\n", 0, i) + 1, text[i + 1:end - 1]))
        i = end
    return result


def _is_helper_copy(literal: str) -> bool:
    """Stricter than the general audit for display-producing helper files.

    Lower-case one-word values such as ``latest``/``night`` are normally
    indistinguishable from ids, but in this tiny closure the real ids are all
    enumerated in ANDROID_HELPER_NON_UI_LITERALS, so they must be gated too.
    """
    return bool(re.search(r"[A-Za-z]", literal)) and not audit.PURE_FORMAT_SPEC.fullmatch(literal)


def _apple_catalog_for(path: str) -> Path:
    if path.startswith("Packages/StrandDesign/"):
        return ROOT / "Packages/StrandDesign/Sources/StrandDesign/Resources/Localizable.xcstrings"
    return ROOT / "Strand/Resources/Localizable.xcstrings"


def _localized_swift_literals(path: Path) -> set[str]:
    """Catalog-backed literals used by SwiftUI or explicit String(localized:)."""
    text = path.read_text(encoding="utf-8")
    literals = {literal for _, literal in audit.swift_string_literals(text)}
    for match in SWIFT_LOCALIZED_CALL.finditer(text):
        quote = match.end() - 1
        end = audit._skip_swift_string_literal(text, quote)
        literals.add(text[quote + 1:end - 1])
    return {literal for literal in literals if audit.is_probably_ui_text(literal)}


def _android_resource_names(path: Path) -> set[str]:
    return set(R_STRING.findall(audit._mask_comments(path.read_text(encoding="utf-8"))))


class HomeLocalizationTest(unittest.TestCase):
    maxDiff = None

    def test_android_home_has_no_audit_findings(self) -> None:
        findings = [row for row in audit.scan_android() if row[0] in ANDROID_HOME_FILES]
        self.assertEqual([], findings, "Unlocalized Android Home UI:\n" + _format_findings(findings))

    def test_android_display_helpers_have_no_raw_ui_copy(self) -> None:
        findings: list[tuple[str, int, str]] = []
        for relative in sorted(ANDROID_DISPLAY_HELPERS):
            for line, literal in _all_kotlin_literals(ROOT / relative):
                if literal in ANDROID_HELPER_NON_UI_LITERALS:
                    continue
                if _is_helper_copy(literal):
                    findings.append((relative, line, literal))
        self.assertEqual([], findings, "Raw Android Home helper copy:\n" + _format_findings(findings))

    def test_android_indirect_home_producers_have_no_raw_ui_copy(self) -> None:
        findings: list[tuple[str, int, str]] = []
        for relative in sorted(ANDROID_INDIRECT_COPY_FILES):
            for line, literal in _all_kotlin_literals(ROOT / relative):
                if literal in ANDROID_INDIRECT_NON_UI_LITERALS:
                    continue
                if _is_helper_copy(literal):
                    findings.append((relative, line, literal))
        self.assertEqual([], findings, "Raw indirect Android Home copy:\n" + _format_findings(findings))

    def test_android_today_runtime_producer_copy_is_resource_backed(self) -> None:
        findings = [
            (relative, line, literal)
            for relative in sorted(ANDROID_SUMMARY_FILES)
            for line, literal in _all_kotlin_literals(ROOT / relative)
            if literal in ANDROID_TODAY_RUNTIME_COPY
        ]
        self.assertEqual([], findings, "Raw Today runtime producer copy:\n" + _format_findings(findings))

    def test_android_score_section_label_is_resolved_at_ui_boundary(self) -> None:
        relative = "android/app/src/main/java/com/noop/ui/ScoringGuideScreen.kt"
        source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
        self.assertNotRegex(source, r"val\s+label\s*:\s*String", "ScoreSection must expose a resource contract")

    def test_android_home_score_labels_and_explain_copy_are_resource_backed(self) -> None:
        for relative in sorted(ANDROID_SUMMARY_FILES):
            home = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
            self.assertNotIn("domain.label", home, "Home score names must be resolved from Android resources")

        guide_relative = "android/app/src/main/java/com/noop/ui/ScoringGuideScreen.kt"
        findings = [
            (guide_relative, line, literal)
            for line, literal in _all_kotlin_literals(ROOT / guide_relative)
            if _is_helper_copy(literal)
            and literal not in {
                "${(sampleFraction * 100).roundToInt()}",
                "scoreCardHighlight",  # an animation label for tooling, never shown
            }
        ]
        self.assertEqual([], findings, "Raw Android score-explainer copy:\n" + _format_findings(findings))

    def test_android_section_header_trailing_copy_does_not_squeeze_title(self) -> None:
        source = (ROOT / "android/app/src/main/java/com/noop/ui/Components.kt").read_text(encoding="utf-8")
        section_header = source.split("fun SectionHeader(", 1)[1].split("// MARK: - StrandTone", 1)[0]
        self.assertIn("if (overline != null || trailing != null)", section_header)
        self.assertIn("Text(title, style = NoopType.title2", section_header)
        self.assertLess(section_header.index("if (trailing != null)"), section_header.index("Text(title"))

    def test_android_analytics_stays_free_of_ui_resources(self) -> None:
        source = (ROOT / "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt").read_text(encoding="utf-8")
        self.assertNotIn("com.noop.R", source)
        self.assertNotIn("androidx.annotation.StringRes", source)
        self.assertIn("enum class Copy", source)

    def test_android_today_chrome_and_click_labels_are_resource_backed(self) -> None:
        patterns = {
            "raw SectionHeader/Overline": re.compile(r"\b(?:SectionHeader|Overline)\s*\(\s*\""),
            "raw onClickLabel": re.compile(r"\bonClickLabel\s*=\s*\""),
        }
        findings = []
        for relative in sorted(ANDROID_SUMMARY_FILES):
            source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
            for kind, pattern in patterns.items():
                findings.extend(
                    (relative, source.count("\n", 0, match.start()) + 1, kind)
                    for match in pattern.finditer(source)
                )
        self.assertEqual([], findings, "Raw Home chrome/a11y copy:\n" + _format_findings(findings))

    def test_android_home_display_formatting_is_not_pinned_to_us_locale(self) -> None:
        display_files = ANDROID_INDIRECT_COPY_FILES | ANDROID_DISPLAY_HELPERS | ANDROID_SUMMARY_FILES
        findings = []
        for relative in sorted(display_files):
            source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
            findings.extend(
                (relative, source.count("\n", 0, match.start()) + 1, "Locale.US")
                for match in re.finditer(r"\b(?:java\.util\.)?Locale\.US\b", source)
            )
        self.assertEqual([], findings, "US-pinned Android Home display formatting:\n" + _format_findings(findings))

    def test_android_home_resources_cover_focus_locales(self) -> None:
        used = set(ANDROID_HOME_SHELL_RESOURCES)
        for relative in ANDROID_HOME_FILES:
            used.update(_android_resource_names(ROOT / relative))

        paths = {"en": ROOT / "android/app/src/main/res/values"}
        paths.update({
            lang: ROOT / f"android/app/src/main/res/{directory}"
            for lang, directory in audit.ANDROID_LOCALE_DIRS.items()
        })
        missing: list[str] = []
        for lang, directory in paths.items():
            # Android merges every values XML file, including feature-specific resources.
            # Inspect each locale independently so English fallback cannot hide missing copy.
            names = {
                node.attrib["name"]
                for path in sorted(directory.glob("*.xml"))
                for node in ET.parse(path).getroot()
                if node.tag in {"string", "plurals"}
            }
            missing.extend(f"{lang}: {name}" for name in sorted(used - names))
        self.assertEqual([], missing, "Missing Android Home resources:\n" + "\n".join(missing))

    def test_apple_home_has_no_audit_findings(self) -> None:
        findings, _ = audit.scan_ios()
        scoped = [row for row in findings if row[0] in APPLE_HOME_FILES]
        self.assertEqual([], scoped, "Unlocalized Apple Home UI:\n" + _format_findings(scoped))

    def test_apple_charge_driver_verdicts_are_complete_catalog_keys(self) -> None:
        source = (ROOT / "Packages/StrandAnalytics/Sources/StrandAnalytics/ChargeDrivers.swift").read_text(
            encoding="utf-8"
        )
        verdict_block = source.split("// MARK: - Plain-English verdicts", 1)[1].split(
            "static func skinTempDevText", 1
        )[0]
        verdicts = set(re.findall(r'(?:return|\?|:)\s*"([^"]+)"', verdict_block))
        # Pin the unique-key set size so syntax changes cannot silently evade extraction.
        self.assertEqual(18, len(verdicts), "Verdict extraction changed; review the catalog contract")

        catalog = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")
        missing = []
        for verdict in sorted(verdicts):
            entry = audit.swift_catalog_lookup(catalog, verdict)
            if entry is None:
                missing.append(f"all: {verdict!r} absent from catalog")
                continue
            for lang in audit.LANGS:
                if not audit._is_translated(entry, lang):
                    missing.append(f"{lang}: {verdict!r}")
        self.assertEqual([], missing, "Missing Charge-driver verdict translations:\n" + "\n".join(missing))

    def test_apple_home_catalog_entries_cover_focus_locales(self) -> None:
        missing: list[str] = []
        catalogs: dict[Path, dict] = {}
        for relative in sorted(APPLE_HOME_FILES):
            catalog_path = _apple_catalog_for(relative)
            catalog = catalogs.setdefault(catalog_path, audit.load_catalog(catalog_path))
            for literal in sorted(_localized_swift_literals(ROOT / relative)):
                entry = audit.swift_catalog_lookup(catalog, literal)
                if entry is None:
                    # The source-finding test reports this with a useful line number.
                    continue
                for lang in audit.LANGS:
                    if not audit._is_translated(entry, lang):
                        missing.append(f"{relative}: {lang}: {literal!r}")

        shell_catalog_path = _apple_catalog_for(APPLE_SHELL_FILE)
        shell_catalog = catalogs.setdefault(shell_catalog_path, audit.load_catalog(shell_catalog_path))
        for literal in sorted(APPLE_HOME_SHELL_CATALOG_KEYS):
            entry = audit.swift_catalog_lookup(shell_catalog, literal)
            if entry is None:
                missing.append(f"{APPLE_SHELL_FILE}: all: {literal!r} absent from catalog")
                continue
            for lang in audit.LANGS:
                if not audit._is_translated(entry, lang):
                    missing.append(f"{APPLE_SHELL_FILE}: {lang}: {literal!r}")
        self.assertEqual([], missing, "Missing Apple Home catalog translations:\n" + "\n".join(missing))

    def test_apple_whoop_brand_and_tint_are_locale_independent(self) -> None:
        source = (ROOT / "Strand/Screens/MetricExplorerView.swift").read_text(encoding="utf-8")
        catalog = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")
        self.assertEqual("WHOOP", catalog["strings"]["Whoop"]["localizations"]["pt-PT"]["stringUnit"]["value"])
        self.assertIn('private let provenanceWhoopBrandName = "WHOOP"', source)

    def test_apple_pt_home_terms_are_localized(self) -> None:
        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        expected = {
            "Push": "Avançar",
            "SYNTHESIS": "SÍNTESE",
            "Still": "Parado",
        }
        for key, value in expected.items():
            self.assertEqual(value, strings[key]["localizations"]["pt-PT"]["stringUnit"]["value"])

    def test_apple_home_semantic_display_contracts(self) -> None:
        app_model = (ROOT / "Strand/App/AppModel.swift").read_text(encoding="utf-8")
        illness = (ROOT / "Packages/StrandAnalytics/Sources/StrandAnalytics/IllnessSignalEngine.swift").read_text(encoding="utf-8")
        readiness = (ROOT / "Packages/StrandAnalytics/Sources/StrandAnalytics/ReadinessEngine.swift").read_text(encoding="utf-8")
        self.assertIn("public enum Message", illness)
        self.assertIn('suppressedBy.append("a hard or late workout")', illness)
        self.assertIn("suppressionReasons.append(.hardOrLateWorkout)", illness)
        self.assertIn('String(localized: "RHR +\\(delta)")', app_model)
        self.assertIn('String(localized: "HRV −\\(percent)%")', app_model)
        # 240c48ae (#1671): the label now carries the reader's unit via UnitFormatter.skinTempSignalPhrase,
        # so the sign and the hardcoded °C moved out of the localized literal.
        self.assertIn('String(localized: "Skin temperature \\(temperature)")', app_model)
        self.assertIn('String(localized: "Respiration up")', app_model)

        self.assertIn("public enum Evidence", readiness)
        self.assertNotIn("LocalizedStringKey(s.detail)", today)

        self.assertIn("badge: Self.whoopBrandName", today)
        self.assertIn('case .nutritionCsv: return String(localized: "Nutrition")', today)
        self.assertIn('case .localCache: return String(localized: "Cached")', today)

        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        for key in (
            "RHR +%lld", "HRV −%lld%%", "Skin temperature %@", "Respiration up",
            "%@ vs %@ %@", "7d %@ / 28d %@", "monotony %@",
        ):
            self.assertIn(key, strings)
            for lang in audit.LANGS:
                self.assertTrue(audit._is_translated(strings[key], lang), f"{lang}: {key}")

    def test_apple_pt_home_terms_are_context_correct(self) -> None:
        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        expected = {
            "Strap battery": "Bateria da pulseira",
            "Needs the strap": "Requer a pulseira",
            "Run down": "Esgotado",
            "Rest HR": "FC repouso",
            "Resting HR": "FC em repouso",
            "Your cards": "Os teus cartões",
            "~%lldh left": "Faltam ~%lld h",
            "%lld days · %lld sleeps": "%1$lld dias · %2$lld noites de sono",
        }
        for key, value in expected.items():
            self.assertEqual(value, strings[key]["localizations"]["pt-PT"]["stringUnit"]["value"])

    def test_apple_home_banner_and_suppression_contract_are_semantic(self) -> None:
        app_model = (ROOT / "Strand/App/AppModel.swift").read_text(encoding="utf-8")
        banner = (ROOT / "Strand/Screens/HealthAlertBanner.swift").read_text(encoding="utf-8")
        illness = (ROOT / "Packages/StrandAnalytics/Sources/StrandAnalytics/IllnessSignalEngine.swift").read_text(encoding="utf-8")

        self.assertIn("struct HealthAlert: Equatable", app_model)
        self.assertIn("let message: IllnessSignalEngine.Message", app_model)
        self.assertIn("@Published var healthAlert: HealthAlert?", app_model)
        self.assertNotIn("? result.copy : nil", app_model)
        self.assertIn("localizedHealthAlertCopy", banner)
        self.assertNotIn("Text(alert)", banner)
        self.assertIn('suppressedBy.append("a hard or late workout")', illness)
        self.assertIn("public enum SuppressionReason", illness)
        self.assertIn("public let suppressionReasons: [SuppressionReason]", illness)

    def test_apple_de_score_glossary_preserves_physiology_terms(self) -> None:
        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        values = [
            entry.get("localizations", {}).get("de", {}).get("stringUnit", {}).get("value", "")
            for entry in strings.values()
        ]
        joined = "\n".join(values)
        self.assertNotIn("Erholungherz", joined)
        self.assertNotIn("Erholungqualität", joined)
        self.assertNotIn("Erholung- und Live-Herzfrequenz", joined)
        for key, expected in {
            "Charge": "Energie",
            "Effort": "Belastung",
            "Rest": "Erholung",
            "How Rest is calculated": "So wird Erholung berechnet",
        }.items():
            self.assertEqual(expected, strings[key]["localizations"]["de"]["stringUnit"]["value"])

    def test_apple_home_count_catalogs_have_real_focus_plural_variations(self) -> None:
        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        for key in ("%lld days", "%lld sleeps", "%lld workouts"):
            for lang in ("en", *audit.LANGS, "it"):
                localization = strings[key].get("localizations", {}).get(lang, {})
                plural = localization.get("variations", {}).get("plural", {})
                self.assertIn("one", plural, f"{lang}: {key} missing singular")
                self.assertIn("other", plural, f"{lang}: {key} missing plural")

    def test_apple_illness_messages_are_not_portuguese_in_other_locales(self) -> None:
        strings = audit.load_catalog(ROOT / "Strand/Resources/Localizable.xcstrings")["strings"]
        keys = (
            "Your body looks strained. Signals up: %@. No alcohol or travel was logged, so consider taking it easy. On-device estimate, not a diagnosis.",
            "You logged feeling unwell, and your signals agree. Take it easy today. On-device estimate, not a diagnosis.",
            "You logged feeling unwell. Take it easy today. On-device estimate, not a diagnosis.",
            "Some signals are up, but you logged %@. That is the more likely explanation. On-device estimate, not a diagnosis.",
            "A few signals are mildly up: %@. Nothing alarming, but a calmer day may help. On-device estimate, not a diagnosis.",
            "Still learning your baseline and keeping an eye on your signals.",
            "Nothing notable. Your signals look like their normal range.",
        )
        for key in keys:
            localizations = strings[key]["localizations"]
            pt = localizations["pt-PT"]["stringUnit"]["value"]
            for lang in ("it", "pl", "ru", "zh-Hans", "zh-Hant"):
                self.assertNotEqual(pt, localizations[lang]["stringUnit"]["value"], f"{lang}: {key}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
