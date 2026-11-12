#pragma once

// Every GOT slot this library replaced, so that a hot reload can give the slots back (see Java_..._nativeRetire in native_refresh_cap.cpp).
// After a module update the new generation loads its OWN copy of this library from the new APK; the old copy stays mapped, and every slot still
// pointing into it would keep the old copy's scheduler running unfed.  The old generation therefore restores the slots before it hands over.

// Records one replaced slot: `replacement` is what this library wrote, `original` what was there before.
void bsbRecordGotPatch(void** slot, void* replacement, void* original);

// True once this copy handed over: its background threads stop at their next tick.
bool bsbNativeRetired();
