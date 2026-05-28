# Next Improvement Candidates (Non-IPv4)

**Context**: Iterations 15–17 brought the hot path from 50 M/s to 118 M/s (+136% cumulative). Vector now exceeds C AVX-512 throughput by 1.66× (118 vs 71.3 M/s). The remaining bottleneck is Vector API safety overhead (~30% range checks absent in C), which cannot be eliminated without JVM-level changes.

---

## Current Hot-Path Port-5 Contention

| # | Operation | Location | Port |
|---|-----------|----------|------|
| 1 | `vpcmpb` (hex classify) | `hexNibblesArithmetic` → `v.compare(GE, 'A')` | p0/p5 |
| 2 | `vpcompressb` (hex extraction) | `r.compress(HOT_COMPRESS_MASK)` | p5 |
| 3 | `vpsrlw` (pairNibbles shift) | `pairNibbles` → `sv.lanewise(LSHR, 8)` | p0/p5 |
| 4 | `vpcompressb` (pair pack) | `pairNibbles` → `pairedBytes.compress(PAIR_MASK)` | p5 |

**2 p5-only (vpcompressb×2) + 2 p0/p5 ops per parse** — down from 3 p5-only + 2 p0/p5 in Iteration 15.

---

## ✅ Idea 1: Skip Validation on Hot Path (DONE in Iteration 15)

**Implemented**: NonDelim mask derived directly from delimiter bits on hot path. Skips `r.compare(GE, 0)` + `toLong()` + validation branch.

**Result**: Part of +116% hot-path improvement.

---

## ✅ Idea 2: Merge Delimiter Detection (DONE in Iteration 15)

**Implemented**: Single vector load for both `:` and `.` detection in single-vector path. Reuses `loadedVec` for hex conversion in Phase 5+6, eliminating a second `fromArray`.

**Result**: Part of +116% hot-path and +52% cold-path improvement.

---

## ✅ Idea 3: Arithmetic Hex Conversion (DONE in Iteration 17)

**What**: Replace the LUT `vpermb` approach with arithmetic-only hex-to-nibble conversion on hot/mixed paths.

**Implemented**:
```java
static ByteVector hexNibblesArithmetic(ByteVector v) {
    ByteVector nibble = v.and((byte) 0x0F);
    VectorMask<Byte> isLetter = v.compare(VectorOperators.GE, (byte) 'A');
    ByteVector letterVal = nibble.add((byte) 9);
    return nibble.blend(letterVal, isLetter);
}
```

**How it works**:
- `v & 0x0F` extracts the lower nibble: digits ('0'-'9'=0x30-0x39) → 0-9, letters (0x41-0x46, 0x61-0x66) → 1-6
- `v >= 'A'` distinguishes letters (≥65) from digits (48-57)
- Letters get `nibble + 9`: 'A'=1+9=10, 'F'=6+9=15, 'a'=1+9=10, 'f'=6+9=15

**Instructions**: `vpandb`(p015) + `vpcmpb`(p05) + `vpaddb`(p015) + `vpblendmb`(p015) = 4 ops, only 1 on port 5

**Port-5 change**: Removes 1 `vpermb` (p5-only). On hot path: 2→1 p5-only, 2→2 p0/p5 (neutral).

**Validation caveat**: This arithmetic approach produces valid 0-15 nibbles for many non-hex chars too (e.g., '@'=0, space=0, 'G'=16). These would pass a `compare(GE, 0)` validation check. **Solution**: Use arithmetic only on hot/mixed paths (where validation is skipped), keep LUT (`hexNibblesLUT`) for cold path where -1 sentinel validation is needed.

**Cold path**: Unchanged LUT approach — `compare(GE, 64)` → `sub(64)` → `blend` → `toShuffle()` → `LUT.rearrange()`. Still uses `vpermb` (port 5), but cold path accounts for only ~30% of throughput.

**Result**: Hot path 112.0→118.4 M/s (+5.7%), cold path unchanged (51.2 M/s, noise).

---

## ✅ Idea 7: Precomputed Masks for Known Lengths (DONE in Iteration 15)

**Implemented**: `MASK_39` and `MASK_16` as `static final VectorMask<Byte>` fields, used in hot path and `pairNibbles.intoArray`.

**Result**: Part of +116% hot-path improvement (avoids `SPECIES.indexInRange(0, 39)` on every call).

---

## ✅ Idea 8: Precomputed HOT_COMPRESS_MASK + PAIR_MASK (DONE in Iteration 16)

**What**: Precompute the nonDelim compress mask for the 39-byte hot path (colon positions are fixed at 4,9,14,19,24,29,34) so no runtime `fromLong`/`kmovq` needed. Also precompute PAIR_MASK for `pairNibbles` compress.

**Implemented**:
```java
static final long HOT_NON_DELIM = ~((1L<<4) | (1L<<9) | (1L<<14) | (1L<<19) | (1L<<24) | (1L<<29) | (1L<<34)) & ((1L<<39) - 1);
static final VectorMask<Byte> HOT_COMPRESS_MASK = VectorMask.fromLong(SPECIES, HOT_NON_DELIM);
static final VectorMask<Byte> PAIR_MASK = VectorMask.fromLong(SPECIES, 0x55555555L);
```

**Savings**: Removes 1 `VectorMask.fromLong(nonDelim)` (which compiles to `kmovq` + port-5 µop) from hot path. Also deferred `nonDelim` computation to mixed-span/cold paths only.

**Result**: Hot path 108.2→111.9 M/s (+3.5%), cold path 50.1→51.8 M/s (+3.4%, noise).

---

## Idea 4: Eliminate Final Compress in pairNibbles (HARD)

**Current `pairNibbles`**:
```java
static ByteVector pairNibbles(ByteVector v) {
    ShortVector sv = (ShortVector) v.reinterpretAsShorts();
    ShortVector low = sv.and((short) 0xFF);
    ShortVector high = (ShortVector) sv.lanewise(VectorOperators.LSHR, 8).and((short) 0xFF);
    ShortVector paired = low.mul((short) 16).or(high);
    ByteVector pairedBytes = (ByteVector) paired.reinterpretAsBytes();
    return pairedBytes.compress(PAIR_MASK);
}
```

**Current cost**: `vpandw`(p015) + `vpsrlw`(p0/5) + `vpandw`(p015) + `vpmullw`(p015) + `vpor`(p015) + `vpcompressb`(p5) = 6 ops, 1 p5-only + 1 p0/5.

**Problem**: `pairedBytes` has 16 paired values at even byte positions (0,2,4,...,30) with zeros at odd positions. The final `compress(PAIR_MASK)` packs them to contiguous lanes 0-15 using `vpcompressb` (port 5).

**Goal**: Eliminate this final compress.

### Approach 4a: Lane extract + scalar pack

Extract 4 longs and pack every-other-byte via bit manipulation:
```java
LongVector lv = (LongVector) pairedBytes.reinterpretAsLongs();
// l0 = [p0, 0, p1, 0, p2, 0, p3, 0]
// l1 = [p4, 0, p5, 0, p6, 0, p7, 0]
// ...
long packLong(long l) {
    return (l & 0xFFL)
         | ((l >>> 16) & 0xFF00L)
         | ((l >>> 32) & 0xFF0000L)
         | ((l >>> 48) & 0xFF000000L);
}
```

**Problem**: Lane extraction (`vextracti64x2`) is also port 5. 4 extracts + 4 scalar packs + 4 stores vs 1 compress + 1 store. More port-5 ops, not fewer.

### Approach 4b: Short-to-byte truncation

The paired shorts already have the correct value in their low byte:
```
After reinterpretAsBytes: [p0, 0, p1, 0, p2, 0, ..., p15, 0]
After reinterpretAsShorts: short[0]=p0, short[1]=p1, ..., short[15]=p15
```

If we could truncate shorts to bytes (like `VPMOVWB` or `VPACKUSWB`), we'd bypass the compress entirely. The Vector API's `convertShape` might do this, but it's not optimized on JDK 21 and may be slower than compress.

### Updated Assessment

At 118 M/s, the final compress is ~8% of cycles. Eliminating it saves 1 p5-only op, potentially gaining ~5%. But every alternative approach either:
- Adds lane extracts (also port 5)
- Needs `convertShape` (unknown performance)
- Requires SWAR-style manual byte packing (scalar)

**Conclusion**: Not worth implementing. The `vpcompressb` is the cheapest way to pack every-other-byte in a 64-byte vector. Revisit if a future JDK adds `VPMOVWB`/`VPACKUSWB` intrinsics for byte truncation.

---

## Idea 5: Direct Hex→Pair (ARCHITECTURAL CHANGE) (VERY HARD)

**What**: Skip the nibble extraction and pairing steps entirely. Go from 32 hex chars directly to 16 output bytes in one fused operation.

### C approach
```
1. vpermb → hex nibbles (32 bytes)
2. vpcompressb → packed nibbles (32 contiguous)
3. vextracti64x2 → low 16 bytes
4. vpmaddubsw ([16,1]*8) → 8 shorts (paired)
5. vpackuswb → 8 bytes
6. Repeat for second half → 8 bytes
Total: 2× vpermb + 2× compress + 2× vextract + 2× maddubs + 1× packuswb
```

### Java approach (current)
```
1. vpandb + vpcmpb + vpaddb + vpblendmb → 32 nibbles (arithmetic, Iteration 17)
2. vpcompressb → 32 contiguous nibbles
3. short-vector pairing (5 ALU ops, port 0/1)
4. vpcompressb → pack to 16 bytes
= 2 p5-only (compress) + 2 p0/5 (vpcmpb, vpsrlw) + 4 p015 ops
```

**Idea 5a**: Larger LUT (32K×8 = 256KB) mapping hex char pairs → output bytes. Not practical for L1 cache.

**Idea 5b**: Two-pass LUT:
```
LUT1: hex char → nibble
LUT2: nibble pair → byte
= 2 vpermb + 1 compress + 1 compress = 4 p5-only ops (worse)
```

**Updated Assessment**: At 118 M/s, we're already 1.66× C AVX-512. The architectural gap (missing `vpmaddubsw` and `vpackuswb`) prevents matching the C approach's instruction count. Without these x86 instructions exposed in the Vector API, any alternative is slower.

**Conclusion**: Not worth implementing without JVM-level intrinsics for `VPMADDUBSW` or `VPACKUSWB`.

---

## 🟡 Idea 6: Checkless Hot Path (PARTIALLY DONE)

**What**: Restructure so the hot path (all-span-4, no `::`, no IPv4) takes a completely validation-free route.

**Status**: Implemented in Iterations 15–17. The hot path now:
- Uses arithmetic hex conversion (no LUT, no validation)
- Uses `HOT_COMPRESS_MASK` precomputed for 39-byte input
- Skips all validation (no `compare(GE, 0)`, no `toLong`, no branch)
- Returns immediately after compress+pair+store

The remaining check on the hot path is the `len - nc == hexGroups * 4` branch condition, which is the routing check itself — no extra validation overhead.

**A full `parseFast` → `parseSafe` split is NOT needed**: The current branch structure already routes hot paths to the fast path and cold paths to the slow path without duplication.

---

## Summary: What's Done / What's Next

### ✅ Implemented (Iterations 7–17)

| Idea | Description | Iteration | Impact |
|------|-------------|-----------|--------|
| 1 | Skip validation on hot path | 15 | Part of +116% hot |
| 2 | Merge delimiter detection | 15 | Part of +116% hot |
| 3 | **Arithmetic hex conversion** | **17** | **+5.7% hot** |
| 6 | Checkless hot path (partial) | 15–17 | Combined with 1+3+8 |
| 7 | Precomputed MASK_39 / MASK_16 | 15 | Part of +116% hot |
| 8 | **Precomputed HOT_COMPRESS_MASK + PAIR_MASK** | **16** | **+3.5% hot** |
| 9 | Short-vector pairing (pairNibbles) | 14 | +12.5% mixed-span |

### Remaining Ideas

| Priority | Idea | Est. Gain | Complexity | Port-5 saved |
|----------|------|-----------|------------|-------------|
| 1 | **Idea 4: Eliminate final compress** | ~5% | Hard | 1 (vpcompressb) |
| 2 | **Idea 5: Direct hex→pair** | ~5%? | Very Hard | 1–2 |

Both ideas have marginal returns at this point (118 M/s ≈ 1.66× C). The Vector API overhead (~30% safety checks) is now the dominant bottleneck that cannot be eliminated without JVM-level changes.

### Note

At 118 M/s (Iteration 17), Vector exceeds C AVX-512 throughput (71.3 M/s) by **1.66×**. Remaining port-5 pressure: 2 p5-only (2× vpcompressb) + 2 p0/p5 ops. Further improvements are diminishing returns — the Vector API overhead (~30% safety checks) is intrinsic to the API.

**Recommendation**: Stop optimizing. The current implementation is faster than hand-tuned C AVX-512 on the same microarchitecture despite the API overhead. Future work should focus on JVM-level intrinsics (VPMADDUBSW, VPACKUSWB, checkless compress) rather than workarounds within the Vector API.
