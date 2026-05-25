# Why Vector & VectorCE Are Not 10x Faster (Updated)

## Baseline: Lemire's C AVX-512

Blog (2026-05-23) on **Xeon Gold 6548N @ 2.8 GHz** with GCC `-O3`:

| Parser | M/s | instr/addr | IPC |
|--------|-----|-----------|-----|
| `inet_pton` (C library) | 5.7 | 954 | 1.56 |
| AVX-512 | **71.3** | **120** | **2.45** |
| Speedup | **12.5×** | 7.9× fewer | |

Our Java on **i9-11950H @ 2.6 GHz** (also Ice Lake, 64-byte vectors) — **current**:

| Parser | 39-byte M/s | vs Scalar | vs C |
|--------|------------|-----------|------|
| Scalar | 7.5 | 1× | 0.11× |
| SWAR | 6.1 | 0.81× | 0.09× |
| SWAROpt | 9.0 | 1.20× | 0.13× |
| VectorCE | 12.3 | 1.64× | 0.17× |
| **Vector** | **28.9** | **3.85×** | **0.41×** |
| C AVX-512 | 71.3 | ~9.5× | 1× |

**Vector now reaches 41% of C throughput** (up from 9% baseline). The remaining gap: C does the entire pipeline in ~11 vector + 40 scalar instructions (120 total), while Java still needs ~300+ instructions for the same work.

---

## Current Hot Profile (compress+pair approach)

From perfasm on `2001:0db8:85a3:0000:0000:8a2e:0370:7334` (39 bytes):

| Activity | % cycles | Instruction |
|----------|----------|-------------|
| `Long.bitCount(hexBits)` | ~26% | `popcnt` |
| Colon position loop + validation | ~18% | `tzcnt` + `blsr` + array stores |
| `VectorMask.fromLong` (compress) | ~11% | mask creation |
| `indexInRange` + `fromArray` (load) | ~7% | vector load |
| LUT `rearrange` (hex convert) | ~5% | `vpermb` |
| `compare` + `blend` + `sub` | ~4% | hi-byte shift |
| `compress` + 2× `rearrange` + `mul` + `or` | ~10% | pairing pipeline |
| `reinterpretAsLongs` + 2 lane extracts | ~3% | output extraction |
| Empty segment detection | ~6% | `col[]` scan |
| Other (branch, alloc, return) | ~10% | misc |

**Key change**: The lane-extraction bottleneck (~50% of cycles, 5× `lv.lane()`) is **eliminated**. The compress+pair approach replaces 5 slow lane extractions + scalar bit-op loop with: 1 compress + 2 constant shuffles + 1 mul + 1 or + 2 lane extracts.

---

## The Compress+Pair Approach (Iteration 7)

For the all-span-4 case (the common 39-byte "full form" addresses), the hex-to-output pipeline is:

```
nibble vector r (64 bytes, 32 hex nibbles + 7 colon-fill + 17 zero)
  ↓ compress(keepMask) — remove 7 colon positions (vpcompressb)
hexNibs (32 contiguous nibbles in lanes 0-31)
  ↓ rearrange(SHUFFLE_EVEN) — select nibbles 0,2,4,...,30
evens (16 bytes, nibble[even] in output positions 0-15)
  ↓ mul((byte)16) — nibble << 4 (vpmullb)
shifted (16 bytes, nibble[even]<<4)
  ↓ or(odds) — (nibble[even]<<4) | nibble[even+1]
result (16 output bytes in lanes 0-15)
  ↓ reinterpretAsLongs() + 2× lane(0), lane(1)
long0 + long1 → byte[16] out
```

Total: **5 vector ops** (compress + 2 rearranges + mul + or) + 2 lane extracts. This replaces the previous ~50 lane-based ops.

### Why it's fast

On AVX-512 (Ice Lake):
- `vpcompressb` (1 uop, 1 cycle latency, 1/3 throughput) — requires mask from `kmovq`
- `vpermb` with static shuffle (1 uop, 1 cycle throughput on Ice Lake)
- `vpmullb` (1 uop, 0.5 cycle throughput on Ice Lake)
- `vpor` (1 uop, 0.5 cycle)
- `vextracti64x2` + `vmovq` for lane extraction (2 uops, ~3 cycles for 2 lanes)

For comparison, the old approach:
- 5× `vextracti64x2` + `vpermq` + `vmovq` for lane extraction (~5 uops each = 25 uops)
- Then 8× shift+mask+copy operations in scalar code

### Limitation: only works for all-span-4

The pair-assembly `nibble[even]<<4 | nibble[even+1]` assumes consecutive nibbles form a single hex group. This is only true when every segment has exactly 4 hex digits. For mixed-span inputs (e.g., `2001:db8:0:0:0:0:0:1` with spans 4,3,1,1,1,1,1,1), the scalar assembly loop is still needed.

---

## Remaining Bottlenecks

### #1: `Long.bitCount(hexBits)` (~26%)

The popcnt intrinsic is fast (1 cycle), but C2 generates a full intrinsic call with bounds checking. The `hexBits` variable is computed as `(~delims) & ((1L << len) - 1)`, which involves two ALU ops + one popcnt.

**Idea**: Cache `(~delims) & ((1L << len) - 1)` — the same value is computed earlier for validation:
```java
long nonDelim = (~delims) & ((1L << len) - 1);
// validate
if ((invalidBits & nonDelim) != 0) return null;
// reuse for compress
int hexChars = Long.bitCount(nonDelim);
```
This eliminates one recomputation.

### #2: Colon position extraction (~18%)

The `while (bits != 0)` loop uses `tzcnt` + `blsr`, which is optimal but still 2-3 uops per colon. For 7 colons on a 39-byte input, that's 14-21 uops.

**Idea**: Use `Long.compress` to pack colon positions into contiguous lanes, then extract as shorts (similar to the C approach). But this adds complexity.

### #3: Mask creation overhead (~11%)

`VectorMask.fromLong(SPECIES, hexBits)` creates a mask from the hex bitmask. On AVX-512 this compiles to `kmovq` (1 uop), but the VM adds object allocation overhead for the `VectorMask<Byte>` wrapper.

**Idea**: The `compress` intrinsic could accept a raw `long` mask instead of requiring a `VectorMask` object. Not possible without API changes.

### #4: Empty segment detection (~6%)

The for-loop over `col[]` to detect empty segments (`start == end`) adds ~6% overhead. For the fast path (no `::`), this is wasted work.

**Idea**: Move the empty detection loop to only execute when `ccPairs == 1` (i.e., `::` is present). For the common case (no `::`), skip the detection entirely.

---

## Improvement Ideas (Updated)

### ✅ Implemented in Iterations 3-7

| Idea | Iteration | Impact |
|------|-----------|--------|
| LUT-based hex conversion (single-LUT workaround) | 3.1 | +0-16% |
| Inline long extraction via reinterpretAsLongs | 4 | +9-23% |
| Hot/cold assembly path split | 5 | +11-13% (long input) |
| Col-based segment bounds (kill arrays) | 6 | +7-13% |
| **Compress+pair vector assembly** | **7** | **+82% (39-byte)** |

### 🎯 High priority

**1. Cache nonDelim bitmask** — the `(~delims) & ((1L << len) - 1)` value is computed twice (once for validation, once for compress). Compute once, reuse.

**2. Skip empty detection for fast path** — the `emptyCount` scan over `col[]` is wasted when `ccPairs == 0`. Move it behind the `if (ccPairs == 1)` guard.

**3. Remove hexGroups*4 check overhead** — the `if (hexChars == hexGroups * 4)` branch determines whether the compress path is taken. For 39-byte inputs this is always true, but the branch check + popcnt adds ~30% overhead. Could use a simpler heuristic: `(len - nc - 1) == hexGroups * 4` which avoids the popcnt.

### 🎯 Medium priority

**4. Multi-vector compress+pair fallback** — For AVX2 (32-byte vectors, SL=32), 39-byte inputs need 2 vectors. The multi-vector fallback currently uses `HEX_BUF`. Extend compress+pair to handle the 2-vector case.

**5. VectorCE compress+pair** — The `compressExpandPath` already uses `compress`. Replace its `intoArray(TMP)` + scalar loop with the pairing approach. Could also eliminate the `grpSizes[]` allocation.

**6. Perfasm re-analysis** — After each iteration, re-profile to identify the new top bottleneck.

### 🎯 Low priority / speculative

**7. Static shuffle from Long.compress** — Instead of `VectorMask.fromLong` + `compress`, use `Long.compress(posBits, colonBits)` to get packed colon positions, then compute segment spans via subtraction. This avoids the vector mask creation overhead entirely.

**8. Stack-allocated scratch arrays** — Escape analysis may already eliminate `col[8]` and `out[16]` allocations. Verify with `-XX:+PrintEscapeAnalysis`.

---

## Update the 13× instruction gap

| Component | C AVX-512 | Java Vector (current) | Factor |
|-----------|-----------|----------------------|--------|
| Hex conversion | 1 (`vpermb`) | 5 ops (sub+compare+blend+toShuffle+rearrange) | 5× |
| Validation | 1 (`vpmovb2m`) | 5 ops (compare+toLong+bitwise) | 5× |
| Group size computation | ~5 vector + 8 scalar | 1 scalar loop + `tzcnt` | ~6× |
| **Hex→byte combine** | 2 (`maddubs`+`cvtepi16`) | **5 ops (compress+2 rearranges+mul+or)** | **2.5×** |
| Memory alloc/free | 0 | 2 arrays (`col[8]`, `out[16]`) | — |
| Overall instructions | ~120 | ~300 | **2.5×** |

The gap has narrowed from **13× to 2.5×** — a 5× improvement through Iterations 3-7.

---

## C Comparison: Remaining Gap

The C code does the full 39-byte parse in ~120 instructions. Our Java Vector now takes ~300 instructions (estimated from the halving of cycles from Iteration 6 → 7).

C's advantages that Java cannot eliminate:
1. **Register allocation**: C has 32 vector registers (zmm0-zmm31) and 16 GP registers — all managed by the compiler. Java's C2 has the same but must also handle object references and safepoints.
2. **Zero abstraction overhead**: C's intrinsic calls map 1:1 to instructions. Java's Vector API layers add ~2-3× instruction count before C2 intrinsifies.
3. **No garbage collection**: No GC write barriers, no object header overhead.
4. **Stack allocation**: Arrays live on the stack in C. In Java, even with escape analysis, array allocation has overhead.

For Vector to reach C-level throughput, we'd need:
- A way to pass colon/dot bitmasks directly to compress (avoid `VectorMask` object)
- Eliminate the `idx.toShuffle()` wrapper (the shuffle validation + range check on JDK 21)
- Use `vpmaddubsw` instead of `mul` + `or` for the pairing step
