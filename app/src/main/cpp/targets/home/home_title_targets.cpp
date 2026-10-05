/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* See home_title_targets.h for the rationale behind the data-patch approach. */

#include "home_title_targets.h"

#include "nativehook/memory_io.h"
#include "nativehook/nhk_base.h"

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <time.h>

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <string_view>

namespace home_title {
namespace {

constexpr const char *kLogTag = "HyperCeilerHomeNative";

/** Log unreadable if the log level or prefs are broken, so keep this unconditional. */
void Say(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "title: %s", message);
}

/**
 * "全部应用" and "All apps" in UTF-16LE, and their equal-length blank replacements.
 * Both spellings are patched because the drawer title is localised and the snapshot
 * carries every shipped translation.
 */
constexpr unsigned char kTitleZh[8] = {0x68, 0x51, 0xe8, 0x90, 0x94, 0x5e, 0x28, 0x75};
constexpr unsigned char kBlankZh[8] = {0x20, 0x00, 0x20, 0x00, 0x20, 0x00, 0x20, 0x00};
/** "All apps" = A l l ' ' a p p s = 8 chars -> 8 spaces. */
constexpr unsigned char kTitleEn[16] = {
    0x41, 0x00, 0x6c, 0x00, 0x6c, 0x00, 0x20, 0x00,
    0x61, 0x00, 0x70, 0x00, 0x70, 0x00, 0x73, 0x00};

struct Needle {
    const unsigned char *text;
    size_t bytes;
    const unsigned char *blank;
};

/** A read-only PT_LOAD range of libapp.so: the only place the literals can live. */
struct RoRange {
    uintptr_t begin;
    size_t bytes;
};

constexpr int kMaxRanges = 8;
/** A pathological image must not turn the scan into an unbounded search. */
constexpr int kMaxHitsPerRange = 32;
/**
 * The Dart image is loaded late and — measured on HyperOS 4 — not through any path that
 * raises a library-load callback we can rely on: the Zygisk-era notes for this feature
 * recorded that hooking `dlopen`/`android_dlopen_ext` never fired because bionic reaches the
 * mapping through an internal syscall. Polling is therefore the primary trigger, not a
 * fallback, and it is bounded so a launcher that never maps the image costs nothing.
 */
constexpr int kMaxPollIterations = 500;
constexpr long kPollIntervalNs = 20L * 1000L * 1000L; // 20 ms -> ~10 s budget

/**
 * Feature gate. Starts off and is turned on by the `HomeDrawerTitle` rule, which owns the
 * preference read — the same push contract the Dock switch uses. The native chain is started by
 * process name alone, so without an explicit push it cannot know the user's choice; defaulting to
 * on would blank the drawer title of every launcher in the module's scope, including users who
 * never enabled the switch.
 */
std::atomic<bool> g_enabled{false};
std::atomic<bool> g_done{false};
std::atomic<bool> g_poll_started{false};
std::atomic<unsigned long> g_hits{0};

/** Accumulator for the dl_iterate_phdr walk. `dl_iterate_phdr` returns whether the walk was
 *  stopped early, not a count, so the callback has to carry the count out of band. */
struct RangeCollector {
    RoRange *out;
    int count;
};

/**
 * Collect read-only, non-executable PT_LOAD ranges of libapp.so.
 *
 * Read-only *and* non-executable is the filter that matters: `.rodata` is the only
 * segment the snapshot's string constants can be in, and restricting to it keeps the
 * scan small (a few MB) and keeps the patch away from code.
 *
 * This only covers the case where the linker reports the image by name. When the library is
 * mapped straight out of the APK the linker does not report it at all — see
 * CollectRangesFromApkMaps, which is the normal shape on HyperOS 4.
 */
int CollectRanges(struct dl_phdr_info *info, size_t, void *context) {
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    const std::string_view path(info->dlpi_name);
    if (!path.ends_with("libapp.so")) return 0;

    auto *collector = static_cast<RangeCollector *>(context);
    int count = collector->count;
    for (int i = 0; i < info->dlpi_phnum && count < kMaxRanges; i++) {
        const ElfW(Phdr) &header = info->dlpi_phdr[i];
        if (header.p_type != PT_LOAD || header.p_memsz == 0) continue;
        const bool readable = (header.p_flags & PF_R) != 0;
        const bool writable = (header.p_flags & PF_W) != 0;
        const bool executable = (header.p_flags & PF_X) != 0;
        if (!readable || writable || executable) continue;
        collector->out[count++] = RoRange{
            static_cast<uintptr_t>(info->dlpi_addr) + static_cast<uintptr_t>(header.p_vaddr),
            static_cast<size_t>(header.p_memsz)};
    }
    collector->count = count;
    return count > 0 ? 1 : 0; // Stop iterating once the image is found.
}

/**
 * Collect every read-only mapping belonging to the launcher, container or not.
 *
 * This is the fallback that does not care how the image got mapped. The Dart snapshot's
 * string constants live in a read-only segment, so any read-only mapping of the launcher is
 * a legitimate place to look for them — whether the loader reported `libapp.so` by name
 * (extracted libs) or mapped it straight out of `base.apk` (STORED entry + default
 * `extractNativeLibs`, the normal HyperOS 4 shape, where `dl_iterate_phdr` reports the APK
 * path instead of the library and no library-load callback fires for it).
 *
 * Keeping this as a plain mapping walk — rather than decoding the ELF inside the APK — means
 * there is no zip-offset or program-header arithmetic to get wrong, and a mapping that turns
 * out not to contain the literal simply costs one bounded scan.
 *
 * The executable mapping is excluded: the literal is data, and staying out of code pages
 * keeps a misidentified mapping from ever being written.
 */
int CollectRangesFromLauncherMaps(RoRange *out, int capacity) {
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return 0;

    char line[512];
    int count = 0;
    while (count < capacity && fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long begin = 0;
        unsigned long long end = 0;
        char perms[5] = {};
        char path[384] = {};
        // "<begin>-<end> perms offset dev inode path" — path may be absent, so 4 fields suffice.
        if (sscanf(line, "%llx-%llx %4s %*x %*s %*s %383s",
                &begin, &end, perms, path) != 4) {
            continue;
        }
        if (path[0] != '/' || strstr(path, "com.miui.home") == nullptr) continue;
        if (perms[0] != 'r' || perms[1] == 'w' || perms[2] == 'x') continue;
        if (end <= begin) continue;
        out[count++] = RoRange{static_cast<uintptr_t>(begin),
            static_cast<size_t>(end - begin)};
    }
    fclose(maps);
    return count;
}




/** Bounded in-place search. Returns the number of sites rewritten in this range. */
int PatchRange(const RoRange &range, const Needle &needle, bool verbose) {
    int patched = 0;
    uintptr_t cursor = range.begin;
    size_t remaining = range.bytes;

    while (remaining >= needle.bytes && patched < kMaxHitsPerRange) {
        const std::byte *haystack = reinterpret_cast<const std::byte *>(cursor);
        // memmem, not a hand-rolled loop: the APK-mapping path can cover a whole 29 MB
        // snapshot and this runs inside a 20 ms poll interval.
        const void *hit = memmem(haystack, remaining, needle.text, needle.bytes);
        if (hit == nullptr) break;
        const std::byte *found = static_cast<const std::byte *>(hit);

        const uintptr_t address = reinterpret_cast<uintptr_t>(found);
        // Fail closed: re-read through the fault-reporting path and compare before writing.
        // A byte that moved between the scan and the write means the image changed underneath us.
        unsigned char confirm[16]{};
        if (!nhk::safe_read(address, std::span<std::byte>(reinterpret_cast<std::byte *>(confirm),
                needle.bytes)) ||
            std::memcmp(confirm, needle.text, needle.bytes) != 0) {
            Say("verify-before-write failed; skipping site");
        } else if (!nhk::write_code_bytes(address,
                       std::span<const std::byte>(reinterpret_cast<const std::byte *>(needle.blank),
                           needle.bytes))) {
            Say("page write refused; skipping site");
        } else {
            patched++;
            if (verbose) Say("patched one site");
        }

        const size_t consumed = static_cast<size_t>(found - haystack) + needle.bytes;
        cursor += consumed;
        remaining -= consumed;
    }
    return patched;
}

} // namespace

/**
 * Poll for the Dart image.
 *
 * The primary trigger, not a belt-and-braces extra: the `on_library_loaded` callback this target
 * is also wired into does not fire for `libapp.so` on HyperOS 4 — the launcher's AOT image is
 * mapped outside the ordinary linker callback path (the same finding is recorded on this
 * target's property hook). Without the worker the patch never ran, which is the reported symptom.
 */
void *PollWorker(void *) {
    for (int i = 0; i < kMaxPollIterations; i++) {
        if (g_done.load(std::memory_order_relaxed)) return nullptr;
        InstallIfNeeded();
        if (g_done.load(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "title: done at poll iteration %d", i);
            return nullptr;
        }
        timespec interval{kPollIntervalNs / 1000000000L, kPollIntervalNs % 1000000000L};
        while (nanosleep(&interval, &interval) != 0 && errno == EINTR) {}
    }
    if (!g_done.load(std::memory_order_relaxed)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
            "title: gave up after %d polls — libapp.so never appeared?", kMaxPollIterations);
    }
    return nullptr;
}

void SetEnabled(bool enabled) {
    g_enabled.store(enabled, std::memory_order_relaxed);
}

/*
 * Apply the drawer-title switch as it arrived on the layout snapshot.
 *
 * Split from SetEnabled because the snapshot is the only trustworthy source: the module's JNI
 * entry point runs inside the launcher process's injected Java rule, which on this ROM is not
 * guaranteed to be dispatched at all (the launcher never reports the `configure` stage, while the
 * Dock keeps working because it is started by process name). A snapshot that arrives here means
 * system_server answered, so the value is authoritative in both directions: turning the feature
 * off has to stop a poller that a previous snapshot started.
 */
void ApplySwitch(bool enabled) {
    const bool was = g_enabled.exchange(enabled, std::memory_order_acq_rel);
    if (was != enabled) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "title: switch from layout snapshot enabled=%d", enabled);
    }
    if (!enabled) return;
    InstallIfNeeded();
    StartPolling();
}

bool Applied() {
    return g_hits.load(std::memory_order_relaxed) > 0;
}

void StartPolling() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    if (g_done.load(std::memory_order_acquire)) return;
    if (g_poll_started.exchange(true, std::memory_order_acq_rel)) return;

    pthread_attr_t attributes{};
    if (pthread_attr_init(&attributes) != 0) {
        g_poll_started.store(false, std::memory_order_release);
        return;
    }
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    pthread_t worker{};
    const int created = pthread_create(&worker, &attributes, PollWorker, nullptr);
    pthread_attr_destroy(&attributes);
    if (created != 0) {
        g_poll_started.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "title: poll worker not started (%d)", created);
    } else {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "title: poll worker started");
    }
}

void InstallIfNeeded() {
    if (g_done.load(std::memory_order_acquire)) return;
    if (!g_enabled.load(std::memory_order_relaxed)) {
        // Not an error: the feature is simply off. Mark nothing, so enabling it later retries.
        // Logged once so a missing switch push is distinguishable from a failed search.
        static std::atomic<bool> warned{false};
        if (!warned.exchange(true, std::memory_order_acq_rel)) {
            Say("disabled (switch not pushed yet) — waiting");
        }
        return;
    }

    RoRange ranges[kMaxRanges]{};
    RangeCollector collector{ranges, 0};
    // Path 1: the linker reports the image by name (extracted-libs shape).
    dl_iterate_phdr(CollectRanges, &collector);
    int count = collector.count;
    if (count <= 0) {
        // Path 2: the image is mapped straight out of base.apk — the normal HyperOS 4 shape,
        // and the one where no library-load callback fires. Any read-only launcher mapping is
        // a candidate; the literal search decides which one actually holds the snapshot.
        count = CollectRangesFromLauncherMaps(ranges, kMaxRanges);
        if (count > 0) {
            __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "title: image not reported by name; scanning %d launcher mapping(s)", count);
        }
    }
    if (count <= 0) {
        // The image is not mapped yet (Flutter loads it late). Leave g_done clear so the
        // poll loop retries.
        return;
    }

    // English spelling: "All apps" (8 UTF-16 chars) blanked with 8 spaces.
    unsigned char blankEn[sizeof(kTitleEn)];
    for (size_t i = 0; i < sizeof(blankEn); i += 2) {
        blankEn[i] = 0x20;
        blankEn[i + 1] = 0x00;
    }

    const Needle needles[] = {
        {kTitleZh, sizeof(kTitleZh), kBlankZh},
        {kTitleEn, sizeof(kTitleEn), blankEn},
    };

    int patched = 0;
    unsigned long scanned_bytes = 0;
    for (int i = 0; i < count; i++) {
        scanned_bytes += ranges[i].bytes;
        for (const Needle &needle : needles) {
            patched += PatchRange(ranges[i], needle, /*verbose=*/false);
        }
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
        "title: scanned %d range(s), %lu bytes, patched %d site(s)",
        count, scanned_bytes, patched);

    g_hits.store(static_cast<unsigned long>(patched), std::memory_order_relaxed);
    if (patched > 0) {
        g_done.store(true, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "title: patched %d site(s) — drawer title blanked (runtime search, not a fixed offset)",
            patched);
    } else {
        // Text changed in this launcher build. Retrying is pointless once the image is
        // fully mapped, so stop after the first resolved attempt.
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
            "title: literal not found in libapp.so read-only segments (text changed?)");
    }
}

/**
 * Poll for the Dart image.
 *
 * This is the primary trigger, not a belt-and-braces extra: the library-load callback the
 * target is wired into is documented (in this same target's property hook) as unreliable for
 * this image — "HYOS maps its AOT libapp outside the ordinary linker callback path". Without
 * this worker the patch simply never ran, which is exactly the reported symptom.
 */

} // namespace home_title
