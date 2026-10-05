/* SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * Launcher app-drawer title removal ("全部应用" / "All apps").
 *
 * ── Why this is a data patch and not a hook ─────────────────────────────
 * MiuiHome on HyperOS 4 is Flutter/Dart: its APK carries no dex at all, and
 * the drawer title is not in any Android resource table (measured: 0 hits
 * across resources.arsc, framework.jar, miui-framework.jar, miuix.jar,
 * services.jar, miui-services.jar). The text lives in the Dart AOT snapshot
 * `libapp.so` as a UTF-16 string constant in a read-only segment, so there is
 * no function to hook — the correct fix is an equal-length in-place rewrite.
 *
 * ── Why the search is done at runtime ───────────────────────────────────
 * A hard-coded offset breaks on every launcher update. Searching the mapped
 * read-only segments for the known UTF-16 literal keeps working as long as
 * the string itself survives, and it is bounded (bounded segment count,
 * bounded hits per segment).
 *
 * ── Why equal length ────────────────────────────────────────────────────
 * A Dart String carries its length in the object header and the snapshot
 * stores absolute offsets. Replacing four characters with four spaces keeps
 * both invariants intact; a shorter or longer replacement would corrupt the
 * snapshot.
 *
 * Provenance: ported from the HyShield Zygisk module (hyper_rust/hyshield,
 * src/home/hook_home.cpp), which established the approach and the three-step
 * locating method. The Zygisk plumbing is not ported — this target runs on
 * the LSPosed native-hook contract that HyperCeiler already uses, and the
 * page write goes through nativehook/memory_io.h instead of a private
 * mprotect helper.
 */
#pragma once

namespace home_title {

/** Install (or re-attempt) the title patch. Idempotent; a no-op while the feature is off or
 *  after a successful patch. Returns immediately when the Dart image is not mapped yet. */
void InstallIfNeeded();

/**
 * Start the polling worker that waits for the Dart image.
 *
 * Required, not optional: the `on_library_loaded` callback this target is also wired into does
 * not fire for `libapp.so` on HyperOS 4 (the launcher's AOT image is mapped outside the ordinary
 * linker callback path). Relies on the worker being the primary trigger.
 */
void StartPolling();

/** Enable or disable the patch. Disabling after a successful patch cannot restore the
 *  original bytes — the segment is read-only and the original text is not retained. */
void SetEnabled(bool enabled);

/**
 * Apply the switch as it arrived on the layout snapshot.
 *
 * Preferred over [SetEnabled] because the snapshot comes from system_server over the launcher's
 * own authenticated transaction, whereas the JNI setter only exists inside the launcher process's
 * injected Java rule — which is not guaranteed to be dispatched. Safe to call repeatedly.
 */
void ApplySwitch(bool enabled);

/** True once the patch has been applied to at least one site. */
bool Applied();

} // namespace home_title
