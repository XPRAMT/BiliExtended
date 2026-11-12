// Lifts Bilibili's IJK playback-rate ceiling (GetMaxPlayRate -> 2.0 / 3.0) in the :ijkservice
// process.  ffp_set_playback_rate (libijkplayer.so) asks libijkffext.so's variadic
// GetAICenterOutput(9, &max, 4, player_id) for the maximum rate and clamps the request to it.
// This file replaces libijkplayer's JUMP_SLOT for that import with a forwarding function that
// raises the returned value for id 9 only.
//
// Everything is found by symbol name and verified by signature (no fixed addresses, no
// build-id): any mismatch means nothing is installed and Bilibili behaves as before.
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#include "got_registry.h"

namespace {

constexpr char kTag[] = "BiliExtended";
// ffp_set_playback_rate: identical first eight instructions in 9.13.0 and 9.17.0.  9.18.0 (checked 2026-11-10, same 240-byte size, same GetAICenterOutput size 1052) differs in
// exactly one field: word 6 is `ldr w3, [x19, #imm]` (the player id) and the struct offset moved from 0x14a8 to 0x14a0, so that imm12 is not compared.
constexpr Elf64_Xword kCallerSize = 240;
constexpr std::array<uint32_t, 8> kCallerPrologue = {
        0xb4000760, 0xfc1e0fe8, 0xa9014ffe, 0xf9400408,
        0xaa0003f3, 0xb4000688, 0xb954aa63, 0x910033e1};
constexpr uint32_t kLdrImm12Bits = 0x003ffc00;
constexpr std::array<uint32_t, 8> kCallerPrologueMask = {
        0xffffffffU, 0xffffffffU, 0xffffffffU, 0xffffffffU,
        0xffffffffU, 0xffffffffU, ~kLdrImm12Bits, 0xffffffffU};
constexpr Elf64_Xword kTargetSize = 1052;
constexpr float kMinLimit = 1.0f;
constexpr float kMaxLimit = 10.0f;

struct LoadedLibrary {
    uintptr_t base = 0;
    std::string name;
    const Elf64_Phdr* phdr = nullptr;
    Elf64_Half phnum = 0;
    const Elf64_Sym* symbols = nullptr;
    const char* strings = nullptr;
    const Elf64_Rela* jumpRelocations = nullptr;
    size_t jumpRelocationBytes = 0;
};

struct SearchState {
    LoadedLibrary player;
    LoadedLibrary ffext;
};

using GetAICenterOutputFn = int (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
        uint64_t, uint64_t);

std::mutex gInstallMutex;
std::atomic<bool> gInstalled{false};
std::atomic<float> gLimit{0.0f};
std::atomic<GetAICenterOutputFn> gOriginal{nullptr};
std::atomic<uint32_t> gLastLoggedFrom{0};
std::atomic<uint32_t> gLastLoggedTo{0};
// The maximum rate ffp_set_playback_rate saw on its last call (after the lift): the request is
// stored as min(request, this), so with the request known the applied rate is known exactly.
std::atomic<float> gObservedMax{0.0f};

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "speed-cap-lift: %s", message.c_str());
}

uintptr_t dynamicPointer(uintptr_t base, Elf64_Addr value) {
    return value >= base ? static_cast<uintptr_t>(value) : base + value;
}

int collectLibraries(dl_phdr_info* info, size_t, void* data) {
    auto* state = static_cast<SearchState*>(data);
    if (info->dlpi_name == nullptr) {
        return 0;
    }
    LoadedLibrary* target = nullptr;
    if (std::strstr(info->dlpi_name, "libijkplayer.so") != nullptr) {
        target = &state->player;
    } else if (std::strstr(info->dlpi_name, "libijkffext.so") != nullptr) {
        target = &state->ffext;
    } else {
        return 0;
    }
    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    const Elf64_Phdr* dynamicHeader = nullptr;
    for (Elf64_Half index = 0; index < info->dlpi_phnum; ++index) {
        if (info->dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamicHeader = &info->dlpi_phdr[index];
        }
    }
    if (dynamicHeader == nullptr) {
        return 0;
    }
    target->base = base;
    target->name = info->dlpi_name;
    target->phdr = info->dlpi_phdr;
    target->phnum = info->dlpi_phnum;
    const auto* dynamic = reinterpret_cast<const Elf64_Dyn*>(base + dynamicHeader->p_vaddr);
    for (const Elf64_Dyn* entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                target->symbols = reinterpret_cast<const Elf64_Sym*>(
                        dynamicPointer(base, entry->d_un.d_ptr));
                break;
            case DT_STRTAB:
                target->strings = reinterpret_cast<const char*>(
                        dynamicPointer(base, entry->d_un.d_ptr));
                break;
            case DT_JMPREL:
                target->jumpRelocations = reinterpret_cast<const Elf64_Rela*>(
                        dynamicPointer(base, entry->d_un.d_ptr));
                break;
            case DT_PLTRELSZ:
                target->jumpRelocationBytes = static_cast<size_t>(entry->d_un.d_val);
                break;
            default:
                break;
        }
    }
    return 0;
}

// .dynsym is followed by .dynstr in Android-linked libraries; the span between the two
// bounds the symbol count.  Returns nullptr when the layout is not as expected.
const Elf64_Sym* findSymbol(const LoadedLibrary& lib, const char* name) {
    if (lib.symbols == nullptr || lib.strings == nullptr
            || reinterpret_cast<uintptr_t>(lib.strings) <= reinterpret_cast<uintptr_t>(lib.symbols)) {
        return nullptr;
    }
    const size_t count = (reinterpret_cast<uintptr_t>(lib.strings)
            - reinterpret_cast<uintptr_t>(lib.symbols)) / sizeof(Elf64_Sym);
    if (count == 0 || count > 400000) {
        return nullptr;
    }
    for (size_t index = 1; index < count; ++index) {
        const Elf64_Sym& symbol = lib.symbols[index];
        if (symbol.st_name != 0 && symbol.st_value != 0
                && ELF64_ST_TYPE(symbol.st_info) == STT_FUNC
                && std::strcmp(lib.strings + symbol.st_name, name) == 0) {
            return &symbol;
        }
    }
    return nullptr;
}

bool insideLoadSegment(const LoadedLibrary& lib, uintptr_t address) {
    for (Elf64_Half index = 0; index < lib.phnum; ++index) {
        const Elf64_Phdr& header = lib.phdr[index];
        if (header.p_type != PT_LOAD) {
            continue;
        }
        const uintptr_t start = lib.base + header.p_vaddr;
        if (address >= start && address < start + header.p_memsz) {
            return true;
        }
    }
    return false;
}

int protectionForAddress(uintptr_t address) {
    FILE* maps = std::fopen("/proc/self/maps", "re");
    if (maps == nullptr) {
        return 0;
    }
    char line[512];
    int protection = 0;
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char permissions[5] = {};
        if (std::sscanf(line, "%llx-%llx %4s", &start, &end, permissions) != 3
                || address < start || address >= end) {
            continue;
        }
        if (permissions[0] == 'r') protection |= PROT_READ;
        if (permissions[1] == 'w') protection |= PROT_WRITE;
        if (permissions[2] == 'x') protection |= PROT_EXEC;
        break;
    }
    std::fclose(maps);
    return protection;
}

bool writeGotSlot(void** slot, void* value, std::string* error) {
    const uintptr_t address = reinterpret_cast<uintptr_t>(slot);
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        *error = "page size unavailable";
        return false;
    }
    const uintptr_t pageStart = address & ~static_cast<uintptr_t>(pageSize - 1);
    const int original = protectionForAddress(address);
    if (original == 0) {
        *error = "GOT page protection unknown";
        return false;
    }
    if (mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize),
            original | PROT_WRITE) != 0) {
        *error = "GOT slot not writable";
        return false;
    }
    *slot = value;
    if (mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize),
            original) != 0) {
        *error = "GOT protection restore failed (hook installed)";
        return false;
    }
    return true;
}

uint32_t floatBits(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

// Replacement for GetAICenterOutput.  The original is variadic; its prologue spills x3-x7 for
// va_list and q0-q7 for floating-point varargs, and Bilibili's callers pass at most one extra
// argument.  Forwarding x0-x7 unchanged therefore reproduces the original call exactly.
// Only (id 9, size 4) is touched, and only to raise the value.
int liftedGetAICenterOutput(uint64_t id, uint64_t out, uint64_t size, uint64_t a3, uint64_t a4,
        uint64_t a5, uint64_t a6, uint64_t a7) {
    const GetAICenterOutputFn original = gOriginal.load(std::memory_order_acquire);
    if (original == nullptr) {
        return -1;
    }
    const int result = original(id, out, size, a3, a4, a5, a6, a7);
    const float limit = gLimit.load(std::memory_order_relaxed);
    if (static_cast<int>(id) == 9 && size == 4 && out != 0 && limit > kMinLimit) {
        float* maxRate = reinterpret_cast<float*>(out);
        const float current = *maxRate;
        if (current >= 1.0f && current < limit) {
            *maxRate = limit;
            const uint32_t from = floatBits(current);
            const uint32_t to = floatBits(limit);
            if (gLastLoggedFrom.exchange(from) != from || gLastLoggedTo.exchange(to) != to) {
                char text[96];
                std::snprintf(text, sizeof(text), "max play rate %.2f -> %.2f", current, limit);
                logInfo(text);
            }
        }
        gObservedMax.store(*maxRate, std::memory_order_relaxed);
    }
    return result;
}

std::string installSpeedCapLift(float limit) {
    std::lock_guard<std::mutex> lock(gInstallMutex);
    if (!(limit > kMinLimit)) {
        gLimit.store(0.0f);
        return gInstalled.load() ? "speed cap lift paused (limit <= 1)"
                                 : "speed cap lift not installed (limit <= 1)";
    }
    if (limit > kMaxLimit) {
        limit = kMaxLimit;
    }
    if (gInstalled.load()) {
        gLimit.store(limit);
        return "speed cap lift active";
    }
    SearchState state;
    dl_iterate_phdr(collectLibraries, &state);
    if (state.player.base == 0) {
        return "speed cap lift skipped: libijkplayer.so not loaded";
    }
    if (state.ffext.base == 0) {
        return "speed cap lift skipped: libijkffext.so not loaded";
    }
    const Elf64_Sym* caller = findSymbol(state.player, "ffp_set_playback_rate");
    if (caller == nullptr) {
        return "speed cap lift skipped: ffp_set_playback_rate not exported";
    }
    if (caller->st_size != kCallerSize) {
        return "speed cap lift skipped: ffp_set_playback_rate size="
                + std::to_string(caller->st_size) + " expected=" + std::to_string(kCallerSize);
    }
    const uintptr_t callerAddress = state.player.base + caller->st_value;
    for (size_t word = 0; word < kCallerPrologue.size(); ++word) {
        uint32_t instruction = 0;
        std::memcpy(&instruction, reinterpret_cast<const void*>(callerAddress + word * 4U), 4);
        if ((instruction & kCallerPrologueMask[word]) != (kCallerPrologue[word] & kCallerPrologueMask[word])) {
            char text[96];
            std::snprintf(text, sizeof(text),
                    "speed cap lift skipped: ffp_set_playback_rate word %zu=%08x expected %08x",
                    word, instruction, kCallerPrologue[word]);
            return text;
        }
    }
    const Elf64_Sym* target = findSymbol(state.ffext, "GetAICenterOutput");
    if (target == nullptr) {
        return "speed cap lift skipped: GetAICenterOutput not exported by libijkffext.so";
    }
    if (target->st_size != kTargetSize) {
        return "speed cap lift skipped: GetAICenterOutput size="
                + std::to_string(target->st_size) + " expected=" + std::to_string(kTargetSize);
    }
    const uintptr_t targetAddress = state.ffext.base + target->st_value;
    if (state.player.symbols == nullptr || state.player.strings == nullptr
            || state.player.jumpRelocations == nullptr) {
        return "speed cap lift skipped: libijkplayer.so PLT metadata unavailable";
    }
    void** slot = nullptr;
    const size_t relocationCount = state.player.jumpRelocationBytes / sizeof(Elf64_Rela);
    for (size_t index = 0; index < relocationCount; ++index) {
        const Elf64_Rela& relocation = state.player.jumpRelocations[index];
        if (ELF64_R_TYPE(relocation.r_info) != R_AARCH64_JUMP_SLOT) {
            continue;
        }
        const char* symbolName = state.player.strings
                + state.player.symbols[ELF64_R_SYM(relocation.r_info)].st_name;
        if (std::strcmp(symbolName, "GetAICenterOutput") == 0) {
            if (slot != nullptr) {
                return "speed cap lift skipped: GetAICenterOutput imported more than once";
            }
            slot = reinterpret_cast<void**>(state.player.base + relocation.r_offset);
        }
    }
    if (slot == nullptr) {
        return "speed cap lift skipped: no GetAICenterOutput JUMP_SLOT in libijkplayer.so";
    }
    const uintptr_t current = reinterpret_cast<uintptr_t>(*slot);
    if (!insideLoadSegment(state.ffext, current)) {
        return "speed cap lift skipped: GOT target is outside libijkffext.so (unbound or"
                " already hooked)";
    }
    if (current != targetAddress) {
        return "speed cap lift skipped: GOT target is not libijkffext's GetAICenterOutput";
    }
    gLimit.store(limit);
    gOriginal.store(reinterpret_cast<GetAICenterOutputFn>(current), std::memory_order_release);
    std::string error;
    if (!writeGotSlot(slot, reinterpret_cast<void*>(liftedGetAICenterOutput), &error)) {
        // writeGotSlot only fails after the write when the protection restore fails.
        if (*slot != reinterpret_cast<void*>(liftedGetAICenterOutput)) {
            gLimit.store(0.0f);
            gOriginal.store(nullptr);
            return "speed cap lift skipped: " + error;
        }
        logInfo(error);
    }
    bsbRecordGotPatch(slot, reinterpret_cast<void*>(liftedGetAICenterOutput), reinterpret_cast<void*>(current));
    gInstalled.store(true);
    char text[160];
    std::snprintf(text, sizeof(text), "installed: GetAICenterOutput GOT in %s hooked, limit=%.2f",
            state.player.name.c_str(), limit);
    logInfo(text);
    return text;
}

}  // namespace

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeObservedMaxRate(JNIEnv*, jclass) {
    return gObservedMax.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeInstallSpeedCapLift(
        JNIEnv* environment, jclass, jfloat limit) {
    const std::string result = installSpeedCapLift(limit);
    return environment->NewStringUTF(result.c_str());
}
