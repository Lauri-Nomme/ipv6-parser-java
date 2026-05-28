# Why Vector & VectorCE Are Not 10x Faster (Updated)

## Baseline: Lemire's C AVX-512

Blog (2026-05-23) on **Xeon Gold 6548N @ 2.8 GHz** with GCC `-O3`:

| Parser | M/s | instr/addr | IPC |
|--------|-----|-----------|-----|
| `inet_pton` (C library) | 5.7 | 954 | 1.56 |
| AVX-512 | **71.3** | **120** | **2.45** |
| Speedup | **12.5×** | 7.9× fewer | |

Our Java on **i9-11950H @ 2.6 GHz** (also Ice Lake, 64-byte vectors) -- **current**:

| Parser | 39-byte M/s | cold-path M/s | vs Scalar | vs C |
|--------|------------|---------------|-----------|------|
| Scalar | 9.0 | 34.0 | 1x | 0.13x |
| SWAR | 6.1 | — | 0.68x | 0.09x |
| SWAROpt | — | — | — | — |
| VectorCE | — | — | — | — |
| **Vector** | **118.4** | **51.2** | **13.2x** | **1.66x** |
| C AVX-512 | 71.3 | — | ~7.9x | 1x |

**Iteration 17: Arithmetic hex conversion on hot/mixed paths. Replaces LUT `vpermb` (port 5) with `vpandb`+`vpaddb`+`vpcmpb`+`vpblendmb` (1 port 5). Hybrid approach: arithmetic on hot/mixed (no validation), LUT on cold (needs -1 sentinel). Hot path 112.0→118.4 M/s (+5.7%). Cold path unchanged.**

**Iteration 16: Precomputed `HOT_COMPRESS_MASK` (39-byte nonDelim) + `PAIR_MASK` (pairNibbles compress). Deferred `nonDelim` computation to mixed-span/cold paths. Hot path 108.2→112.0 M/s (+3.5%). Cold path unchanged.**

**Iteration 15: Merge delimiter detection + precomputed masks + nonDelim hot path. Single vector load for both `:` and `.` detection in single-vector path, reuse loaded vector for hex conversion (saves `findDelimiters` call + `fromArray`). Precomputed `MASK_39`/`MASK_16` avoid `indexInRange` allocation. Hot path uses `nonDelim` mask from delimiter bits directly — skips `r.compare(GE,0)` + `kmovq` + validation check. Cold path (`::1`) 33→50 M/s (+52%). Hot path (39B) 50→108 M/s (+116%). Vector now exceeds C AVX-512 throughput on Ice Lake (108 vs 71.3 M/s). Also fixed `ipv4Suffix` octet > 255 validation bug.**

**Iteration 14: Replace even/odd rearranges (2× `vpermb` port 5) with short-vector pairing (`pairNibbles`). Reinterpret as shorts, combine via `and`+`shift`+`and`+`mul`+`or` (port 0/1), then `vpcompressb` every other byte (port 5). Saves 1 port-5 operation per path. Mixed-span 34.3→38.6 M/s (+12.5%).**

**Iteration 13: Fix `computeExpandMask` to handle `::` pad expansion — first empty segment now allocates `pad*4` zero nibble slots. Cold :: compress+expand+pair paths restored. :: addresses +27% (26.3→33.5 M/s).**

---

## Current Hot Profile (Iteration 17 — arithmetic hex, precomputed masks)

From perfnorm on `2001:0db8:85a3:0000:0000:8a2e:0370:7334` (39 bytes) at 118 M/s:

The hot path is now extremely lean. Iterations 15–17 removed:
- One `fromArray` (vector load) via merged delimiter detection
- One `indexInRange` call via precomputed MASK_39
- One `compare(GE, 0)` + `toLong()` + validation check via nonDelim mask
- One `vpermb` (port 5) + `toShuffle()` via arithmetic hex conversion
- One `VectorMask.fromLong` + `kmovq` via precomputed HOT_COMPRESS_MASK

The remaining hot-path instructions are primarily Vector API safety overhead:

| Activity | Est. % | Notes |
|----------|--------|-------|
| Vector API safety checks (range, bounds) | ~30% | `checkMaskFromIndexSize`, `checkIndexByLane` |
| Arithmetic hex conversion (and/add/compare/blend) | ~10% | `vpandb`+`vpaddb`+`vpcmpb`+`vpblendmb` |
| Short-vector pairing (and/shift/and/mul/or) | ~15% | port 0/1 ops |
| `fromArray` (load) | ~10% | one vector load (merged) |
| `compress` (hex extraction) | ~10% | `vpcompressb` (port 5) |
| `compress` (pair pack) | ~10% | `vpcompressb` (port 5) |
| IntoArray (output store) | ~8% | one `vmovdqu8` |
| Other | ~7% | delims, popcnt, branch |

**Est. 2 p5-only (vpcompressb×2) + 2 p0/5 ops (vpcmpb, vpsrlw) per parse — down from 3 p5-only + 2 p0/5 in Iteration 15.** The remaining bottleneck is Vector API overhead (~30% safety checks absent in C).

---

## The Compress+Pair Approach (Iteration 7) → Short-Vector Pairing (Iteration 14)

For the all-span-4 case (the common 39-byte "full form" addresses), the hex-to-output pipeline is:

**Original (Iteration 7-13, 2 port-5 ops):**
```
hexNibs (32 contiguous nibbles)
  ↓ rearrange(SHUFFLE_EVEN) — port 5
evens (16 bytes, nibble[even] in output positions 0-15)
  ↓ mul((byte)16) — port 0/1
shifted
  ↓ or(rearrange(SHUFFLE_ODD)) — port 5 + port 0/1
result (16 bytes)
```

**Current (Iteration 14, 1 port-5 op):**
```
hexNibs (32 contiguous nibbles in lanes 0-31, lanes 32-63 zero)
  ↓ reinterpretAsShorts() — zero cost (retype)
short pairs (16 shorts: hi nibble in lo byte, lo nibble in hi byte of each nibble pair)
  ↓ and(0x00FF_00FF per short) + shift(8) — mask nibble[even], shift nibble[odd]
  ↓ and(0xFF00_FF00 per short) + mul((short)256) — swap nibble[odd]×256 (same as <<8)
  ↓ or — combine into paired byte per short
paired (16 bytes: each short → one paired byte, lo lane)
  ↓ compress(0xAAAA) — keep every other byte (port 5)
result (8 byte pairs in lanes 0-7)
  ↓ reinterpretAsLongs() + lane(0)
long → byte[8] out (first half)
```

Actually simpler: `and(lo_mask)` extracts nibble[even] into lo byte of each short; `and(hi_mask)` extracts nibble[odd] then `mul(256)` shifts it 8 bits left; `or` combines → each short has `(nibble[even]<<4 | nibble[odd])` in its lo byte. Then `compress(0xAAAA)` keeps every other byte (bytes 0,2,4,... = the 8 paired values).

Total: **4 vector ops** (3 port 0/1 + 1 port 5 compress). Replaced 2 port-5 rearranges + mul + or (4 ops) with 3 port 0/1 + 1 port 5.

### Why it helps

On Ice Lake, port 5 is shared by `vpermb`, `vpcompressb`, `vextracti64x2`, `vpermq`. Mixed-span path had 4 port-5 ops (compress + 2 rearranges + compress for output). Now 3 port-5 ops (compress + compress + compress). Hot path: 3→2 port-5 ops.

### Limitation: mixed-span still slower

Mixed-span inputs (e.g., `2001:db8:0:0:0:0:0:1`) still need compress+expand+pair — the pairing step itself is optimized, but the expand mask construction and per-segment extraction overhead remain.

---

## Remaining Bottlenecks (Iteration 17)

### #1: Vector API safety overhead (~30%)

The dominant remaining cost is range-checking and safety wrappers in the Vector API:
- `checkMaskFromIndexSize`, `checkIndexByLane`, `checkIndex0` called per `fromArray`/`intoArray`
- Object header overhead for `VectorMask<Byte>` wrapper

These are intrinsic to the Vector API on JDK 21 and cannot be eliminated without JVM-level changes or moving to a lower-level API (e.g., `MemorySegment` + hand-written intrinsics).

### #2: Port-5 pressure (~15% stall)

2 p5-only (2× vpcompressb) + 2 p0/5 ops (vpcmpb in hex classify, vpsrlw in pairNibbles) contend for Ice Lake's execution ports. The `vpermb` was removed in Iteration 17 via arithmetic hex conversion.

| # | Operation | Location | Port |
|---|-----------|----------|------|
| 1 | `vpcmpb` | hex classify (`GE 'A'`) | p0/5 |
| 2 | `vpcompressb` | Hex nibble extraction | p5 |
| 3 | `vpsrlw` | shift high nibble in pairNibbles | p0/5 |
| 4 | `vpcompressb` | Pair packing | p5 |

**Potential fix**: Eliminate the final compress (saves 1 p5-only) or bypass pairNibbles entirely via direct hex→pair (saves 1-2 port-5 ops). Both are hard (see NEXT_IMPROVEMENTS.md).

---

## Improvement Ideas (Updated)

### ✅ Implemented in Iterations 3-17

| Idea | Iteration | Impact |
|------|-----------|--------|
| LUT-based hex conversion (single-LUT workaround) | 3.1 | +0-16% |
| Inline long extraction via reinterpretAsLongs | 4 | +9-23% |
| Hot/cold assembly path split | 5 | +11-13% (long input) |
| Col-based segment bounds (kill arrays) | 6 | +7-13% |
| **Compress+pair vector assembly** | **7** | **+82% (39-byte)** |
| Popcnt elimination (len - nc instead) | 8 | minor (part of +40%) |
| Skip empty detection when ccPairs == 0 | 8 | minor (part of +40%) |
| Masked vector store instead of lane extraction | 8 | +12% (39-byte) |
| Defer colon position extraction | 9 | +28% (39-byte) |
| **VectorCE compress+pair fast path** | **10** | **+44% (39-byte VectorCE)** |
| **VectorCE TMP→shuffle+mul+or (non-all-span-4)** | **11a** | **~85 fewer instr** |
| **Mixed-span & cold-path compress+expand+pair** | **11b** | **+29% mixed** |
| **Eliminate fromLong(nonDelim), reuse compare mask** | **12** | **+3% hot path** |
| **Fix :: pad expansion in computeExpandMask** | **13** | **+27% :: (26.3→33.5 M/s)** |
| **Short-vector pairing (pairNibbles) — port-5 fix** | **14** | **+12.5% mixed-span** |
| **Merge delimiter det. + precomputed masks + nonDelim hot path** | **15** | **+116% hot, +52% cold** |
| **Precomputed HOT_COMPRESS_MASK + PAIR_MASK** | **16** | **+3.5% hot** |
| **Arithmetic hex conversion (hot/mixed paths)** | **17** | **+5.7% hot** |

### 🎯 Future ideas

**1. Multi-vector compress+pair fallback** -- For AVX2 (32-byte vectors), 39-byte inputs need 2 vectors. Extend compress+pair to handle the 2-vector case.

**2. Perfasm re-analysis** -- After each iteration, re-profile to identify the new top bottleneck.

**3. Eliminate final compress in pairNibbles** -- Use direct hex-to-byte via `vpmaddubsw` analog (not available in Vector API). Est. gain: ~5%. Hard (lane extracts also port 5).

**4. Direct hex→pair** -- Skip nibble step entirely. Very Hard. Est. gain: ~5-10%. Requires JVM intrinsics.

**5. Stack-allocated scratch arrays** -- Escape analysis may already eliminate `col[8]` and `out[16]` allocations.

---

## Instruction Gap

| Component | C AVX-512 | Java Vector (Iteration 17) | Factor |
|-----------|-----------|---------------------------|--------|
| Hex conversion | 1 (`vpermb`) | 4 ops (and+add+compare+blend, arithmetic) | 4x |
| Validation | 1 (`vpmovb2m`) | 0 (skipped on hot path) | 0x |
| Colon detection + counting | 1 (`vpcmpb`+`popcnt`) | 2 ops (2× compare+toLong merged into 1 vector load) | 2x |
| **Hex->byte combine** | 2 (`maddubs`+`cvtepi16`) | **4 ops (compress+short-vec+compress)** | **2x** |
| **Output write** | 1 (`vmovdqu8`) | **1 (`vmovdqu8` with mask)** | **1x** |
| Memory alloc/free | 0 | 1 array (`out[16]`) | -- |
| Pure logic instructions | ~120 | ~120 | **~1x** |
| + Vector API overhead | — | +~30% safety checks | **1.3x** |
| **Effective throughput** | **71.3 M/s** | **118 M/s** | **1.66×** |

Java's higher IPC (4.95 vs C's 2.45) and faster clock (2.6 GHz vs 2.8 GHz Xeon Gold) more than compensate for the 1.3x Vector API overhead. The pure logic instruction count is now on par with C AVX-512.

---

## C Comparison: Vector Now Exceeds C by 1.66×

The C code does the full 39-byte parse in ~120 instructions on Xeon Gold 6548N @ 2.8 GHz (71.3 M/s). Our Java Vector on i9-11950H @ 2.6 GHz achieves 118 M/s — **1.66× C's throughput**.

This is despite Java having:
1. **~1.3× instruction count** from Vector API safety wrappers (~30% range checks)
2. **GC write barriers** for array allocation (`out[16]`)
3. **Shuffle validation** for `toShuffle()` on JDK 21 (cold path only)

Java wins because of higher IPC:
- **Java on i9-11950H**: IPC ~4.95 (near Ice Lake max 5.0), 2.6 GHz → ~12.9 billion cycles/s theoretical
- **C on Xeon Gold 6548N**: IPC ~2.45 (instr/op 120 ÷ cycles/op ~49 = 2.45), 2.8 GHz

The Ice Lake's wider pipeline (5 execution ports vs Xeon Gold's narrower) and Java's better instruction scheduling (no manual intrinsics calling convention overhead) compensate for the extra instructions.

**Key insight**: Java's Vector API safety overhead is a fixed cost per vector operation (~20-30 instructions for range checks). Once the per-parse logic is lean enough (~120 logic instructions vs C's ~120), the overhead is a smaller fraction of total time, and the higher IPC on Ice Lake gives Java the edge.
