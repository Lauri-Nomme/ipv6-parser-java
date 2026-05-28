# Next Improvement Candidates (Non-IPv4)

**Context**: Iteration 15 (merged delimiter detection, precomputed masks, nonDelim hot path) brought hot path to 108 M/s (+116%) and cold path to 50 M/s (+52%). Vector now exceeds C AVX-512 throughput (108 vs 71.3 M/s). The remaining bottleneck is Vector API safety overhead (~30% range checks absent in C), which cannot be eliminated without JVM-level changes.

---

## Current Hot-Path Port-5 Ops (3 total)

| # | Operation | Location | Type |
|---|-----------|----------|------|
| 1 | `vpermb` (LUT rearrange) | `LUT.rearrange(idx.toShuffle())` | Hex conversion |
| 2 | `vpcompressb` (hex extraction) | `r.compress(VectorMask.fromLong(SPECIES, nonDelim))` | First compress |
| 3 | `vpcompressb` (pair pack) | `pairNibbles` → `compress(0x55555...)` | Second compress |

Removed `kmovq` (Iteration 15 nonDelim mask optimization) — down from 4 to 3 port-5 ops.

---

## ✅ Idea 1: Skip Validation on Hot Path (DONE in Iteration 15)

**Implemented**: NonDelim mask derived directly from delimiter bits on hot path. Skips `r.compare(GE, 0)` + `toLong()` + validation branch.

**Result**: Part of +116% hot-path improvement.

---

## ✅ Idea 2: Merge Delimiter Detection (DONE in Iteration 15)

**Implemented**: Single vector load for both `:` and `.` detection in single-vector path. Reuses `loadedVec` for hex conversion in Phase 5+6, eliminating a second `fromArray`.

**Result**: Part of +116% hot-path and +52% cold-path improvement.

---

## Idea 3: Arithmetic Hex Conversion (MEDIUM)

**What**: Replace the LUT `vpermb` approach with arithmetic-only hex-to-nibble conversion. Currently:
```
v.compare(GE, 64) → v.sub(64) → blend → toShuffle → LUT.rearrange (vpermb, PORT 5)
```

Replace with arithmetic:
```
v.sub(48) → compare(GE, 65) → v.sub(55) → blend → compare(GE, 97) → v.sub(87) → blend
```

**Current**:
```java
VectorMask<Byte> isHi = v.compare(VectorOperators.GE, (byte) 64);
ByteVector idx = v.blend(v.sub((byte) 64), isHi);
ByteVector r = LUT.rearrange(idx.toShuffle());
```

**Proposed**:
```java
ByteVector d = v.sub((byte) 48);                              // assume digit
VectorMask<Byte> isLetter = v.compare(VectorOperators.GE, (byte) 65);
ByteVector d2 = d.blend(v.sub((byte) 55), isLetter);           // uppercase fixup
VectorMask<Byte> isLower = v.compare(VectorOperators.GE, (byte) 97);
ByteVector r = d2.blend(v.sub((byte) 87), isLower);            // lowercase fixup
```

**Port-5 change**: Removes 1 `vpermb` (port 5). Adds 2 compares + 2 blends + 2 subs (all port 0/1).

**Net port-5 change**: -1 (good). Net total ops: +3 (acceptable trade to relieve bottleneck).

**Saves**: 1 port-5 op (vpermb). On hot path: 4→3 port-5 ops. On mixed-span: 3→2 port-5 ops.

**Caveat**: `toShuffle()` is still needed... wait, no! With arithmetic conversion, we don't need `rearrange` at all! So `toShuffle()` is also eliminated. But we already counted `vpermb` removal. The `toShuffle()` elimination is an added bonus (avoids shuffle index validation on JDK 21).

**Full savings**: 1 vpermb (port 5) + 1 toShuffle overhead (scalar validation). Cost: 2 extra blends + 2 extra subs + 1 extra compare. Net throughput gain uncertain without profiling.

**Validation required**: The `keep` mask from `r.compare(GE, 0)` still works — invalid hex chars produce -1 (0xFF) from the sub operations. But with arithmetic, the -1 detection is different:

For '0'-'9': r = v - 48 → [0, 9] ✓
For 'A'-'F': r = v - 55 → [10, 15] ✓
For 'a'-'f': r = v - 87 → [10, 15] ✓
For invalid (< '0'): e.g., '!' (33): d = 33-48 = -15 → -1 after blend chain... depends on exact arithmetic.
For invalid (between '9' and 'A'): e.g., ':' (58): d = 10, isLetter false (58 < 65), so r = 10. False positive!
- ':' (58) is a delimiter, not in hex chars. But it's in `delims`, so it gets filtered by the `delims` check.
- Other chars in [58, 64]: ';' '<' '=' '>' '?' '@' — these would produce 10-16.
- Actually, 58-48 = 10 (';' = 59 → 11, '<' = 60 → 12, ...) — these ARE false positives because they're NOT delimiters but get nonzero nibble values.

So the arithmetic conversion needs proper range checking. The `compare(GE, 0)` mask will catch these (10-16 is >= 0, so they'd pass validation). We need `compare(GE, 0) & compare(LE, 15)` or more complex range checks for digits AND letters.

For digits: `v >= 48 && v <= 57` → `t = v - 48`, check `t >= 0 && t <= 9`
For uppercase: `v >= 65 && v <= 70` → `t = v - 55`, check `t >= 10 && t <= 15`
For lowercase: `v >= 97 && v <= 102` → `t = v - 87`, check `t >= 10 && t <= 15`

This adds complexity. The LUT approach handles all this in one lookup.

**Better arithmetic approach**: Use a single 128-entry LUT (byte[128]) and load it with `fromArray`, then just index with `v` directly (no toShuffle needed):

```java
// 128-entry LUT: byte[128], indices 0-127 cover ASCII
ByteVector r = LUT128.rearrange(v.reinterpretAsShorts().toShuffle());
```

Wait, `rearrange` takes a `VectorShuffle<Byte>`, not a short vector. And we'd need to extend bytes to shorts to work with a >64 entry LUT. This gets complicated.

**Alternative**: Use two `vpermb` + blend — one for the 0-63 range, one for 64-127 range. But that's 2 port-5 ops instead of 1. Not better.

**Conclusion**: Arithmetic hex conversion is interesting but has validation issues (false positives for chars between '9' and 'A'). Would need additional range-check masking, adding complexity. The LUT approach is cleaner. Skip this unless profiling shows vpermb as the top bottleneck.

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
    return pairedBytes.compress(VectorMask.fromLong(SPECIES, 0x55555555L));
}
```

**Problem**: `pairedBytes` has 16 paired values at even byte positions (0,2,4,...,30) with zeros at odd positions (1,3,5,...,31). The final `compress(0x55555555)` packs them to lanes 0-15 using `vpcompressb` (port 5).

**Goal**: Eliminate this final compress.

### Approach 4a: Lane extract + scalar pack

After `reinterpretAsBytes`, extract 4 longs (lanes 0-3, bytes 0-31) and pack every other byte via scalar bit manipulation:
```java
LongVector lv = (LongVector) pairedBytes.reinterpretAsLongs();
long l0 = lv.lane(0);  // bytes 0-7:  [p0, 0, p1, 0, p2, 0, p3, 0]
long l1 = lv.lane(1);  // bytes 8-15: [p4, 0, p5, 0, p6, 0, p7, 0]
long l2 = lv.lane(2);  // bytes 16-23: [p8, 0, p9, 0, p10, 0, p11, 0]
long l3 = v.lane(3);   // bytes 24-31: [p12, 0, p13, 0, p14, 0, p15, 0]
```

Pack each long by extracting bytes 0,2,4,6:
```java
// Pack bytes from positions 0,2,4,6 → contiguous
// Using bit masking and shifting:
long packLong(long x) {
    // x bytes: [p0, 0, p1, 0, p2, 0, p3, 0] (LE: byte0=LSB)
    long even = x & 0x00FF00FF00FF00FFL;    // [p0, 0, p1, 0, p2, 0, p3, 0]
    long odd  = (x >>> 8) & 0x00FF00FF00FF00FFL; // [0, p0, 0, p1, 0, p2, 0, p3]
    // We want [p0, p1, p2, p3] in low 32 bits:
    // even has p0 in byte 0, p1 in byte 2, p2 in byte 4, p3 in byte 6
    // even_compressed = (even & 0xFF) | ((even >> 16) & 0xFF_00L) | ((even >> 32) & 0xFF_00_00L) | ((even >> 48) & 0xFF_00_00_00L)
    // Or simpler: shift+mask
    return (even & 0x00000000000000FFL)
         | ((even & 0x0000000000FF0000L) >>> 8)   // byte 2 → byte 1
         | ((even & 0x000000FF00000000L) >>> 16)   // byte 4 → byte 2
         | ((even & 0x0000FF0000000000L) >>> 24)   // not right...
         | ((even & 0xFF00000000000000L) >>> 48);  // byte 6 → byte 3? No, 48-bit shift is too much
}
```

This is messy. The `vextracti64x2` for lane extraction is also port 5 on Ice Lake. So we'd save one compress but add lane extracts (also port 5). Not a net win.

### Approach 4b: Vector pack with shift + blend

After pairing, the paired values are at positions 0,2,4,...,30. What if we perform a vectorized pack using shifts and blends?

```java
ByteVector pairedBytes = (ByteVector) paired.reinterpretAsBytes();
// pairedBytes bytes: [p0, 0, p1, 0, p2, 0, ..., p14, 0, p15, 0]
// Also: pairedBytes lanes 32-63 are all zero

// Shift right by 1 byte: [0, p0, 0, p1, ..., 0, p14, 0, p15, 0, ...]
ByteVector shifted = pairedBytes.lanewise(VectorOperators.LSHR, 8);  // wait, LSHR on bytes? No.
```

`VectorOperators.LSHR` on bytes does logical shift of each byte, not byte-granular shift. Not applicable.

What about reinterpreting as shorts and doing arithmetic?
```java
ShortVector sv = (ShortVector) pairedBytes.reinterpretAsShorts();
// sv[0] = p0 | 0<<8 = p0 (LE: low byte = p0, high byte = 0 = the second short)
// Actually: bytes [p0, 0, p1, 0] → as shorts in LE → short[0] = p0 | 0<<8 = p0, short[1] = p1 | 0<<8 = p1
// So the shorts already have the paired value in their low 16 bits!
```

Wait, that changes things. After `reinterpretAsBytes()`, the bytes are:
```
[p0, 0, p1, 0, p2, 0, p3, 0, p4, 0, p5, 0, p6, 0, p7, 0, 0...]
```

After `reinterpretAsShorts()`, each short is (byte0 | byte1<<8):
```
short[0] = p0 | 0<<8 = p0
short[1] = p1 | 0<<8 = p1
short[2] = p2 | 0<<8 = p2
...
short[15] = p15 | 0<<8 = p15
short[16..31] = 0
```

So we have 16 shorts, each containing one paired value! The paired values are ALREADY in short-lane position 0-15 as shorts. We don't need to pack them — we just need to go from shorts to bytes.

Can we truncate shorts to bytes? `VPACKUSWB` or `VPMOVWB` (truncate signed words to unsigned bytes). In the Vector API, this is `convertShape`:

```java
// Not sure if this exists or works:
ByteVector result = pairedBytes.reinterpretAsShorts()
    .convertShape(VectorOperors.TRUNCATE, SPECIES, 0);
```

But `convertShape` might not be optimized on JDK 21. It could be slow.

**Alternative**: Extract the shorts as longs via `reinterpretAsLongs()` and pack the low bytes:

```java
LongVector lv = (LongVector) pairedBytes.reinterpretAsShorts().reinterpretAsLongs();
// Each long has 2 shorts: long[0] = p0 | p1<<16 | ...
// We want bytes: p0, p1, p2, ..., p15
// lv.lane(0): bytes [p0, ..., p7, ?, ?, ...]
```

Hmm, actually:
- short[0] = p0, short[1] = p1, short[2] = p2, ..., short[7] = p7 (these are in the low 16 bytes)
- reinterpretAsLongs on the short vector: each long has 4 shorts
- long.lane(0): p0, p1, p2, p3 (8 bytes: [p0, 0, p1, 0, p2, 0, p3, 0])

Wait no, shorts are 2 bytes each. So:
- short[0] at byte 0-1: [p0 (byte 0), 0 (byte 1)]
- short[1] at byte 2-3: [p1 (byte 2), 0 (byte 3)]
- ...

When reinterpreted as longs (8 bytes each), the first long (lane 0) is bytes 0-7:
```
[p0, 0, p1, 0, p2, 0, p3, 0]
```

We need to pack bytes at positions 0,2,4,6 → 0,1,2,3. This is the same packing problem as before.

### Approach 4c: Direct in-register packing via multiply

There's a classic SWAR technique to pack bytes using multiplication:
```java
long l = lv.lane(0);  // [p0, 0, p1, 0, p2, 0, p3, 0]
// l & 0x00FF00FF00FF00FFL = [p0, 0, p1, 0, p2, 0, p3, 0] (unchanged)
// (l >>> 8) & 0x00FF00FF00FF00FFL = [0, p0, 0, p1, 0, p2, 0, p3]
// We want: [p0, p1, p2, p3]
// Trick: (l * 0x0001000000010000L) >>> 48 or similar bit permute
```

For packing 4 bytes at positions 0,2,4,6 into bytes 0,1,2,3 within a long:
```
l = [p0, 0, p1, 0, p2, 0, p3, 0]
l >> 8 = [0, p0, 0, p1, 0, p2, 0, p3]
l & 0xFF_00_FF_00_FF_00_FF_00L = [0, p0, 0, p1, 0, p2, 0, p3]  // same as l>>8
Wait, that's wrong. (l & 0xFF_00_FF_00_FF_00_FF_00L) where 0xFF is at odd byte positions:
0xFF_00_FF_00_FF_00_FF_00 = mask for bytes 7,5,3,1
l & this mask = [0, 0, p1, 0, p3]? No.

Let me be precise. LE bytes:
l byte 0 = p0, byte 1 = 0, byte 2 = p1, byte 3 = 0, byte 4 = p2, byte 5 = 0, byte 6 = p3, byte 7 = 0

l & 0x00FF00FF00FF00FF (mask: bytes 0,2,4,6) gives: [p0, 0, p1, 0, p2, 0, p3, 0]
l & 0xFF00FF00FF00FF00 (mask: bytes 1,3,5,7) gives: [0, 0, 0, 0, 0, 0, 0, 0]

We need:
result = (l & 0xFF) | ((l >>> 16) & 0xFF00L) | ((l >>> 32) & 0xFF0000L) | ((l >>> 48) & 0xFF000000L)

But wait - l >>> 16 moves byte 2's value to byte 0, l >>> 32 moves byte 4's value to byte 0, etc.

Actually:
result = (l & 0x00FF)
       | ((l >>> 16) & 0x00FF_00L)     // byte 2 → byte 1
       | ((l >>> 32) & 0x00FF_00_00L)  // byte 4 → byte 2
       | ((l >>> 48) & 0x00FF_00_00_00L); // byte 6 → byte 3

Check:
l = p0 | (p1<<16) | (p2<<32) | (p3<<48)
Wait that's wrong. In memory:
byte 0 = p0 (LSB)
byte 1 = 0
byte 2 = p1
byte 3 = 0
byte 4 = p2
byte 5 = 0
byte 6 = p3
byte 7 = 0

As a 64-bit LE value:
l = p0 | (0 << 8) | (p1 << 16) | (0 << 24) | (p2 << 32) | (0 << 40) | (p3 << 48) | (0 << 56)
  = p0 | (p1 << 16) | (p2 << 32) | (p3 << 48)

Now:
(l & 0xFF) = p0
((l >>> 16) & 0xFF_00L) = (p1 << 0) & 0xFF_00L = p1 << 8? No, p1 is already in bits 16-23, so l >>> 16 = p1 | (p2 << 16) | (p3 << 32). & 0xFF00 = (p1 << 8).

Wait, l >>> 16: p0 disappears (shifted out), p1 moves to bits 0-7, p2 moves to bits 16-23, p3 moves to bits 32-39.
l >>> 16 = p1 | (p2 << 16) | (p3 << 32)
(l >>> 16) & 0xFF00 = p1 & 0xFF00? No, p1 fits in 8 bits, so p1 << 0, and & 0xFF00 keeps bit 8 if p1 had it. But p1 is 8-bit max (0-255). So (l >>> 16) & 0xFF00 = p1 << 8? Actually p1 is in bits 0-7 after the shift. & 0xFF00 masks bits 8-15. p1 << 8 would be bit 8. But we already shifted, so... 

Let me think in terms of how Java works:
```java
long l = p0 | ((long)0 << 8) | ((long)p1 << 16) | ((long)0 << 24) | ((long)p2 << 32) | ((long)0 << 40) | ((long)p3 << 48) | ((long)0 << 56);
```
= p0 | ((long)p1 << 16) | ((long)p2 << 32) | ((long)p3 << 48)

We want: packed = p0 | ((long)p1 << 8) | ((long)p2 << 16) | ((long)p3 << 24)

Using the formula:
```java
long result = (l & 0x00FFL)                    // p0 in bits 0-7
            | ((l >>> 16) & 0xFF00L)           // p1 in bits 8-15
            | ((l >>> 32) & 0xFF0000L)          // p2 in bits 16-23
            | ((l >>> 48) & 0xFF000000L);       // p3 in bits 24-31
```

Check:
(l & 0x00FFL) = p0 in bits 0-7
(l >>> 16) = p1 | (p2 << 16) | (p3 << 32)
(l >>> 16) & 0xFF00L = p1 << 8 (keeps bits 8-15)
(l >>> 32) = p2 | (p3 << 16)
(l >>> 32) & 0xFF0000L = p2 << 16
(l >>> 48) = p3
(l >>> 48) & 0xFF000000L = p3 << 24

Result: p0 | (p1 << 8) | (p2 << 16) | (p3 << 24) ✓

This works! So we can pack 4 bytes from a long in 4 ALU ops (and + 3 shifts + or).

**Full approach**:
```java
// After pairing, pairedBytes has data at bytes 0,2,4,...,30
LongVector lv = (LongVector) pairedBytes.reinterpretAsLongs();
long l0 = lv.lane(0);  // [p0, 0, p1, 0, p2, 0, p3, 0]
long l1 = lv.lane(1);  // [p4, 0, p5, 0, p6, 0, p7, 0]
long l2 = lv.lane(2);  // [p8, 0, p9, 0, p10, 0, p11, 0]
long l3 = lv.lane(3);  // [p12, 0, p13, 0, p14, 0, p15, 0]

long pack4(long l) {
    return (l & 0xFFL)
         | ((l >>> 16) & 0xFF00L)
         | ((l >>> 32) & 0xFF0000L)
         | ((l >>> 48) & 0xFF000000L);
}
long p0 = pack4(l0);
long p1 = pack4(l1);
long p2 = pack4(l2);
long p3 = pack4(l3);

// Write 16 bytes
U.putLong(out, 0, p0);  // VarHandle or Unsafe
U.putLong(out, 8, p1);  // p2 and p3 are zero (only first 8 paired values matter)
// Wait, 32 nibbles = 16 paired bytes. p0 = byte[0-3], p1 = byte[4-7], p2 = byte[8-11], p3 = byte[12-15]
```

But we need 16 output bytes, and the lane extract + pack gives us 4 longs × 4 packed bytes = 16 bytes. Then we need to write:

```java
out[0] = (byte)p0; out[1] = (byte)(p0 >>> 8); out[2] = (byte)(p0 >>> 16); out[3] = (byte)(p0 >>> 24);
out[4] = (byte)p1; out[5] = (byte)(p1 >>> 8); ...
```

Could use `Long.byArray` via `VarHandle` or direct writes. But this is 4× lane extracts (port 5) + scalar packing (port 0/1) + 4 variable stores. The current approach is 1 compress (port 5) + 1 intoArray (1 store with vmovdqu8).

Lane extractions (`vextracti64x2`) are port 5. So 4 lane extracts = 4 port-5 operations vs. the current 1 compress (port 5) + 0 lane extracts. Net: much worse!

Actually, wait. We already need to extract longs for the output. The current `intoArray` does a single store. If we use the approach above, we add 4 lane extracts + 4 stores. The `intoArray` in the current code is ONE store, not 4 lane extracts. So we'd be adding a LOT of work.

The `intoArray(out, 0, indexInRange(0, 16))` is a single `vmovdqu8 [out], zmm` instruction — one store, no extracts. Compress already packed the data into lanes 0-15.

So approach 4a (lane extract + scalar pack) is strictly worse — more port-5 ops.

**Conclusion for Idea 4**: Not worth it. The compress in pairNibbles is the cheapest way to pack every-other-byte. Any alternative approach that eliminates it adds more port-5 pressure elsewhere.

---

## Idea 5: Direct Hex→Pair (ARCHITECTURAL CHANGE) (HARD)

**What**: Skip the nibble extraction step entirely. Go directly from hex chars to paired bytes in one vector operation, similar to C's `_mm256_maddubs_epi16` pipeline.

**C approach**:
```
1. vpermb → hex nibbles (32 bytes)
2. vpcompressb → packed nibbles (32 bytes → 32 contiguous nibbles in low 32B)
3. vextracti64x2 → extract low 16 bytes (16 nibbles? no, 32 bytes → YMM)
4. vpmaddubsw ([16,1]*8) → 8 shorts (paired)
5. vpackuswb → 8 bytes
6. Repeat steps 3-5 for second half → 8 bytes
Total: 2× vpermb + 2× compress + 1× vextract + 1× maddubs + 1× packuswb
≈ 7 vector ops for 16 output bytes
```

**Our current Java approach**:
```
1. vpermb → hex nibbles (64 bytes, 32 nibbles)
2. vpcompressb → packed nibbles (32 contiguous)
3-7. short-vector pairing (5 ALU ops)
8. vpcompressb → pack paired bytes (16 → 8 contiguous)
= 2 port-5 + 5 port-0/1
(Applied twice in parallel for 16 output bytes? No, all 32 nibbles are processed at once.)
```

Wait, actually the C code processes 32 hex chars in two batches of 16 because `vpmaddubsw` and `vpackuswb` are YMM (256-bit) operations. But our Java approach handles all 32 nibbles in one 64-byte vector. So we're already doing better!

The 16 shorts from pairing represent all 8 output bytes (each short's low byte is the paired value). The `compress` at the end removes the zero high bytes. 

For a direct hex→pair approach, we'd need to combine hex digit validation and pairing into one step. This is hard without `vpmaddubsw`.

**Idea 5a**: Use a larger LUT (32K×8 = 256KB for a byte lookup) that maps hex character pairs to output bytes. Not practical for L1 cache.

**Idea 5b**: Two-pass LUT with pair accumulation:
```
LUT1: hex char → nibble (current, vpermb)
LUT2: nibble pair → byte (another vpermb)
Total: 2 vpermb + 1 compress + 1 compress
```

This would be: 2 vpermb + 2 compress = 4 port-5 ops. Worse than our current 2 compress + 1 rearrange = 3! (Not counting the kmovq.)

**Conclusion for Idea 5**: Our current approach (compress + short-arithmetic + compress) is already close to optimal for the available Vector API instructions. The C code benefits from `vpmaddubsw` (not available in Vector API) and `vpackuswb` (not directly available). Without these instructions, we can't match C's instruction count.

---

## 🟡 Idea 6: Checkless Hot Path (PARTIALLY DONE)

**What**: Restructure the parse method so that the hot path (all-span-4, no ::, no IPv4) takes a completely validation-free route. Move all validation checks to a separate slow path.

**Status**: Partially implemented in Iteration 15. The hot path now skips hex validation (`compare(GE, 0)` + `toLong()` + validation check) by using the nonDelim mask directly. The `len - nc == 32` check is still done (as part of the branch routing). A full speculative-execution pattern (`parseFast` → fallback) has not been implemented.

**Current structure**: One parse() method with conditional branches. Hot and cold paths share delimiter detection, validation, and hex conversion.

**Proposed structure**: Split into `parseFast()` (no validation) and `parseSafe()` (with validation). Call `parseFast()` when len <= 39 and no `::` or `.` detected. If it returns null (garbage), fall back to `parseSafe()`.

**This is basically a "speculative execution" pattern**: assume valid input, parse at maximum speed, and only validate if the result looks wrong.

Example:
```java
public static byte[] parse(byte[] input, int off, int len) {
    byte[] fast = parseFast(input, off, len);
    if (fast != null) return fast;
    return parseSafe(input, off, len);
}
```

Where `parseFast`:
- Skips ALL validation (no isHex check, no span validation, no segment count check)
- Assumes all-span-4, no `::`, no IPv4
- If any assumption fails, returns null

This means the hot path would just do: load → hex convert → compress → pair → store. No branches, no validation.

**Potential gain**: Remove ~8-10 instructions from the ~20 instruction hot path. ~30-50% fewer ops. But branches and null checks add overhead.

**Caveats**:
- `parseFast` and `parseSafe` code duplication
- `parseFast` returning null on every input that isn't all-span-4 means the fallback runs for mixed-span inputs too — but they'd be slower if `parseFast` fails and we call `parseSafe` as well
- Actually, the mixed-span path already handles that case: the `len - nc == hexGroups * 4` check routes to the all-span-4 path, and the mixed-span path is the else branch. So we don't need a separate function — just restructure the branches.

**Simpler version**: Move the hot path code to the beginning of the method, before validation:
```java
if (len <= SL) {
    // Load + hex convert (needed regardless)
    ...
    // Check if hot path applicable
    if (emptyCount == 0 && !hasDot && len - nc == hexGroups * 4) {
        // Skip validation: compress+pair
        ByteVector hexNibs = r.compress(VectorMask.fromLong(SPECIES, (~delims) & ((1L << len) - 1)));
        pairNibbles(hexNibs).intoArray(out, 0, SPECIES.indexInRange(0, 16));
        return out;
    }
    // Fall through to validated path
}
```

This is essentially Idea 1 (skip validation on hot path) combined with a code restructure. The hot path skips the `keep` compare + toLong + validation check, and skips the `len-nc` branch (the check becomes part of the outer if-clause instead of a nested branch).

Maybe the direction is:
- Precompute `nonDelim = (~delims) & ((1L << len) - 1)` after delimiter detection
- Check `len - nc == 32` early
- If all-span-4 hot path: compress(nonDelim mask) → pair → store (skip all validation)

**Recommendation**: Worth doing. Combine with Idea 1 and Idea 2 for a consolidated hot path.

---

## ✅ Idea 7: Precomputed Masks for Known Lengths (DONE in Iteration 15)

**Implemented**: `MASK_39` and `MASK_16` as `static final VectorMask<Byte>` fields, used in hot path and `pairNibbles.intoArray`.

**Result**: Part of +116% hot-path improvement (avoids `SPECIES.indexInRange(0, 39)` on every call).

---

## Summary: What's Done / What's Next

### ✅ Implemented in Iteration 15

| Idea | Description | Status |
|------|-------------|--------|
| 1 | Skip validation on hot path (nonDelim mask from delims) | Done |
| 2 | Merge delimiter detection (single vector load) | Done |
| 6 | Checkless hot path restructuring | Partially done (combined with Idea 1) |
| 7 | Precomputed MASK_39 / MASK_16 | Done |

### Remaining Ideas

| Priority | Idea | Est. Gain | Complexity | Port-5 saved |
|----------|------|-----------|------------|-------------|
| 1 | **Idea 3: Arithmetic hex conversion** | ~3-5%? | Medium | 1 (vpermb) |
| 2 | **Idea 4: Eliminate final compress** | ~5-8%? | Hard | 1 (vpcompressb) |
| 3 | **Idea 5: Direct hex→pair** | ~10%? | Very Hard | 2 |

### Note

At 108 M/s, Vector already exceeds C AVX-512 throughput (71.3 M/s). Further improvements are diminishing returns. The Vector API overhead (~30% safety checks) is intrinsic to the API. The most impactful remaining target would be port-5 pressure: 3 port-5 ops/parse (vpermb + 2× vpcompressb) on Ice Lake's single-port-5 execution unit.
