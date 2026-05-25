# Why Vector & VectorCE Are Not 10x Faster

## Baseline: Lemire's C AVX-512

Blog (2026-05-23) on **Xeon Gold 6548N @ 2.8 GHz** with GCC `-O3`:

| Parser | M/s | instr/addr | IPC |
|--------|-----|-----------|-----|
| `inet_pton` (C library) | 5.7 | 954 | 1.56 |
| AVX-512 | **71.3** | **120** | **2.45** |
| Speedup | **12.5×** | 7.9× fewer | |

Our Java on **i9-11950H @ 2.6 GHz** (also Ice Lake, also 64-byte vectors):

| Parser | M/s | instr/op | IPC |
|--------|-----|----------|-----|
| Scalar | 9.08 | 2,775 | 5.37 |
| Vector | 11.65 | 1,956 | 4.83 |
| VectorCE | 13.79 | 1,578 | 4.41 |
| Best speedup | **1.5×** | — | — |

**The headline gap: C AVX-512 = 120 instructions/parse; Java VectorCE = 1,578 instructions/op.** That's 13× more instructions for the same logical algorithm. The gap is not in the vector hardware — it's in how much *scalar scaffolding* surrounds each vector operation.

---

## Root Cause 1: Only 20% of instructions are vector ops

From perfasm, the single hot method `Ipv6ParserVector::parse` contains *everything* — inlined. C2 compiles it as one giant method (version 4, ~800+ lines of assembly). The hot regions show:

| Activity | % cycles (old) | % cycles (current) |
|----------|---------------|-------------------|
| Nibble extraction via reinterpretAsLongs | ~27% (intoArray) | **~4%** (register→scalar) |
| `segStart`/`segEnd` array fill (scalar loops) | ~20% | **~0%** (eliminated via col-based) |
| Vector hex conversion (LUT rearrange) | ~4% | ~4% |
| `findDelimiters` load+compare+accumulate | ~11% | ~11% |
| Segment boundary & empty-detection loops | ~10% | **~0%** (merged into assembly) |
| Output assembly loop | ~8% | ~8% |
| Collecting/validation | ~12% | ~8% |
| GC/alloc overhead | ~4% | ~0% |
| **Actual vector ops** | ~4% | **~15%** |

**Key changes**:
- Iteration 4: `intoArray` → `reinterpretAsLongs()` (register extraction), eliminating 64-byte writes
- Iteration 5: Hot/cold path split for assembly loop
- Iteration 6: `segStart[]`/`segEnd[]` arrays eliminated — compute from `col[]` on-the-fly. Removed 2 allocations, 2 fill loops, 1 validation loop.

**Compare: C AVX-512 data flow**

The C code's entire parse processes 45 bytes through these instructions:

```
vmovdqu8   str ← input          // 1 load
vpcmpb    colons ← str == ':'   // 1 compare  → 64-bit mask
vpcmpb    dots   ← str == '.'   // 1 compare  → 64-bit mask
vpermw    str ← LUT[str]        // 1 hex convert (two 64-entry LUTs via permutex2var)
vpmovb2m  err ← str == 0xFF     // 1 validate (move mask)
vpcompressb comp ← str, mask     // 1 compress
vpexpandb  pad ← comp, mask      // 1 expand
vpmaddubsw res ← pad * 0x0110   // 1 multiply-accumulate (nibbles → bytes)
vpmovwb   out ← res              // 1 pack (truncate 16→8 bit)
vmovdqu8  [ptr] ← out            // 1 store (16 bytes)
```

That's ~11 vector instructions for the entire hex pipeline. All the position arithmetic (colon spacing, double-colon detection, segment counts) is done with **bit-level** operations on the mask registers — `_blsr_u64`, `_tzcnt_u32`, `_popcnt_u64`, bit shifts — in about 40 scalar instructions. Total: **~120 instructions per parse**.

---

## Root Cause 2: Java has 4× the scalar pipeline work

The C code discovers group sizes with bit arithmetic:

```c
// colons_bitvector has bits set at each colon position
// compressed_index = compress(index_reg, colons_bitvector)
// → 16 bytes: position of each colon (0, 4, 7, ...)
// colon_location = cvtepu8_epi32(compressed_index)   // zero-extend to 32-bit
// difference = colon_location - alignr(colon_location, 0, 15)  // subtract adjacent
// num_digits = difference - 1  // strip colon itself
```

In 5 vector instructions + 3 bit ops, C gets a 16-element array of per-group hex-digit counts. Adding double-colon padding uses `_mm512_maskz_expand_epi32` — another 1 instruction.

**Java** does the equivalent with 4 explicit int arrays:

```java
int[] col = new int[8];                   // allocation
col[nc++] = numberOfTrailingZeros(bits);  // bit-scan loop
int[] segStart = new int[segs];           // allocation
int[] segEnd   = new int[segs];           // allocation
// fill arrays with for-loops             // 3 scalar loops
int[] grpSizes = new int[hexGroups];      // allocation
// yet another loop to compute sizes
```

That's 4 array allocations and ~6 scalar loops to do what C does in ~8 instructions.

---

## Root Cause 3: Per-byte validation via bit-scan loop

The C code validates all hex digits with one instruction:

```c
error |= _mm512_movepi8_mask(str);
```

This produces a 64-bit mask with bits set wherever the hex conversion produced 0xFF (invalid). Then:

```c
error |= (_mm512_movepi8_mask(str) & copy_mask);  // check only non-delim bytes
```

**Java Vector** does:

```java
byte[] hexVals = convertHex(input, off, len);   // full vector pass, writes to array
long nonDelimBits = ...;                         // bitmask of non-delim positions
long tmp = nonDelimBits;
while (tmp != 0) {                               // BIT-SCAN LOOP
    int p = Long.numberOfTrailingZeros(tmp);     // per-byte extraction
    if (hexVals[p] < 0) return null;
    tmp &= tmp - 1;
}
```

This bit-scan loop iterates once per hex digit (up to 32 iterations), with a read from `hexVals[]` and a branch each time. The perfasm confirms this is ~12% of cycles.

**VectorCE** has a better approach (direct vector compare):

```java
VectorMask<Byte> invalid = nibs.compare(VectorOperators.LT, (byte) 0);
long bad = invalid.toLong() & validLanes;
if (bad != 0) return false;
```

This contributes to VectorCE being faster (1,578 vs 1,956 instr/op).

---

## Root Cause 4: Intermediate array allocations and copies

| Phase | Vector | VectorCE |
|-------|--------|----------|
| hex convert | `byte[45]` hexVals (written, then read) | `byte[64]` padded + `byte[64]` tmp |
| segments | `int[8]` col, `int[8]` segStart, `int[8]` segEnd | same + `int[8]` grpSizes |
| output | `byte[16]` out | `byte[16]` out |

VectorCE also does `System.arraycopy(input, off, padded, 0, len)` — a full 45-byte copy before loading into a vector.

Each allocation incurs:
- GC write barrier overhead
- Zeroing (for int arrays)
- Loop to fill values

The C code allocates nothing — it uses stack variables and registers. The 120 instructions include zero memory management.

---

## Root Cause 5: Java Vector API abstraction overhead

Each Vector API call goes through multiple layers:

```
ByteVector::compare(VectorOperators.EQ, delim)
  → Byte512Vector::compare (dispatches to template method)
  → VectorIntrinsics::compare
  → C2 intrinsic (vpcmpeqb)
  → returns mask as long → wrapped in VectorMask<Byte> object
```

The C code is just `_mm512_cmpeq_epu8_mask(str, colon)` — one intrinsic call directly to one instruction.

The broadcast operations for constants (`ByteVector.broadcast(SPECIES, (byte)'0')`) create new vector values each time. The C code uses `_mm512_set1_epi8('0')` which is folded at compile time into an embedded constant in the instruction encoding.

**Update**: The hex conversion was later optimized to use a single-LUT `rearrange` (compile to `vpermb`), replacing the 13-op chain with 5 ops:

```java
VectorMask<Byte> isHi = v.compare(VectorOperators.GE, (byte) 64);      // 1 compare
ByteVector idx = v.blend(v.sub((byte) 64), isHi);                       // 1 sub + 1 blend
ByteVector nibs = LUT.rearrange(idx.toShuffle());                       // 1 rearrange → vpermb
```

This brings hex conversion much closer to C's `_mm512_permutex2var_epi8` — the remaining overhead is the shift+blend workaround for `toShuffle()`'s range limitation on JDK 21.

**Original code** (now replaced) — the 13-op compare+blend chain:

```java
ByteVector v0 = v.sub(Z0);                                                     // 1 sub
VectorMask<Byte> digit = v0.compare(VectorOperators.GE, (byte) 0)
    .and(v0.compare(VectorOperators.LE, (byte) 9));                           // 2 compares + 1 and
VectorMask<Byte> upper = v.compare(VectorOperators.GE, A)
    .and(v.compare(VectorOperators.LE, F));                                   // 2 compares + 1 and
VectorMask<Byte> lower = v.compare(VectorOperators.GE, a)
    .and(v.compare(VectorOperators.LE, f));                                   // 2 compares + 1 and
ByteVector r = N1;                                                            // 1 broadcast
r = r.blend(v0, digit);                                                       // 1 blend
r = r.blend(v0.sub((byte) 7),  upper);                                        // 1 sub + 1 blend
r = r.blend(v0.sub((byte) 39), lower);                                        // 1 sub + 1 blend
```

That's 4 compares, 3 blends, 3 subs, 2 ands, 1 broadcast = **13 vector ops** per 64-byte chunk for hex conversion.

C does it in **1 instruction**: `_mm512_permutex2var_epi8(lookup_lo, str, lookup_hi)`.

---

## Root Cause 6: VectorCE expand mask building is scalar

In `compressExpandPath`, the expand mask is built bit-by-bit:

```java
long expandBits = 0;
int bitPos = 0;
for (int gs : grpSizes) {
    if (gs == 0) {
        bitPos += 4;
    } else {
        int shift = 4 - gs;
        for (int k = 0; k < gs; k++)
            expandBits |= 1L << (bitPos + shift + k);
        bitPos += 4;
    }
}
```

This inner loop iterates over each hex digit position, setting bits one at a time. The C code computes the expand mask with:

```c
__m256i expand_mask_creation_register = _mm256_setr_epi8(
    0, 0, 0, 0, 0, 0, 0, 0xff, 0, 0, 0xff, 0xff, 0, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, ...);
// permutevar picks the right row based on digit count (1-4)
__mmask32 expand_mask = _mm256_movepi8_mask(
    _mm256_permutevar8x32_epi32(expand_mask_creation_register, num_digits_between_colons));
```

2 instructions: permute + move mask. The Java scalar loop contributes significantly to branch misses (VectorCE has 0.205 vs Vector's 0.042 branch misses/op).

---

## Summary: The 13× instruction gap

| Component | C AVX-512 | Java VectorCE | Factor |
|-----------|-----------|---------------|--------|
| Hex conversion | 1 (`vpermb`) | 5 vector ops (sub + compare + blend + rearrange) | 5× |
| Validation | 1 (`vpmovb2m`) | bit-scan loop + array read + branch | ~20× |
| Group size computation | ~5 vector + 8 scalar | 6 scalar loops + 4 array allocs | ~40× |
| Expand mask | 2 (`permutevar` + `movepi8`) | scalar bit loop per digit | ~30× |
| Memory alloc/free | 0 | 4+ allocations, GC write barriers | — |
| Hex digit → byte combine | 2 (`maddubs` + `cvtepi16`) | scalar loop with shift-accumulate | ~15× |
| Overall instructions | 120 | 1,578 | **13×** |

---

## Improvement Ideas

### 1. Eliminate intermediate arrays (highest impact)

Instead of `convertHex` writing to `byte[45]` and then reading back in a bit-scan loop, combine validation with conversion. The pattern: vector-load → convert → validate-mask (one `toLong()`) → if-bad bail → write-to-out. This eliminates the hexVals array entirely and the bit-scan loop.

Current flow:
```
load → convert → write hexVals[] → bit-scan read hexVals[] → assemble out
```

Target:
```
load → convert → mask-check → assemble out (no intermediate array)
```

VectorCE partially achieves this but still allocates `padded`, `tmp`, and `grpSizes`.

### 2. Eliminate `System.arraycopy` for padding

Instead of `arraycopy` + `fromArray`, use `ByteVector.fromArray(SPECIES, input, off, loadMask)` directly — the load mask handles the partial load. Then compress/expand work on the loaded vector. This saves 45 bytes of copy + allocation.

### 3. Eliminate `tmp[]` allocation in `compressExpandPath`

Currently:
```java
ByteVector paddedNibs = nibs.expand(expandMask);
byte[] tmp = new byte[sl];          // allocation
paddedNibs.intoArray(tmp, 0);
for (int g = 0; g < hexGroups; g++) {
    // read from tmp
}
```

Instead, use `compare` + `toLong` or `reinterpretAsIntegral` to extract nibble pairs directly from the vector without going through a byte array. The combine operation could be done with a vector multiply-accumulate if we could map `a*16 + b` to `_mm256_maddubs_epi16`.

### 4. Compute group sizes with bit operations on colon bits

The C approach: compress colon positions, compute differences by subtracting shifted copy. Java can do the same:

```java
// colonBits has bits set at each colon position
// Long.compress(indexBits, colonBits) → packed colon positions
// Then compute differences = adjacent subtraction
```

This replaces array allocations and for-loops with a few Long operations.

### 5. Pre-compute constants outside the benchmark loop

Vector API broadcasts inside `convertHex` are re-executed every call. They should be `static final` class fields.

### 6. Split hot/cold paths to help C2 specialization

The `parse` method is too large for optimal C2 compilation. Split so the success path (which is hot) gets more aggressive optimization, while error paths (cold) are separated.

### 7. Hex conversion via lookup table (applied)

The `_mm512_permutex2var_epi8` approach does hex conversion in 1 instruction. We replaced the 13-op compare+blend chain with a single-LUT `rearrange`, but with a critical workaround:

**The `toShuffle` range problem**: `ByteVector.toShuffle()` on JDK 21 validates shuffle indices against `VLENGTH` (64 for `Byte512Vector`), throwing `IllegalArgumentException` for any byte value ≥ 64. This means the two-table form `lo.rearrange(v.toShuffle(), hi)` crashes on uppercase/lowercase hex chars (A-F = 65-70, a-f = 97-102).

**Workaround**: Subtract 64 from bytes ≥ 64 before creating the shuffle, then use a single 64-entry LUT:

```java
VectorMask<Byte> isHi = v.compare(VectorOperators.GE, (byte) 64);
ByteVector idx = v.blend(v.sub((byte) 64), isHi);
ByteVector nibs = LUT.rearrange(idx.toShuffle());
```

This is 5 vector ops (compare, sub, blend, toShuffle, rearrange) vs 13 for the original compare+blend chain. The single `rearrange` compiles to `vpermb` on AVX-512, giving the same hardware instruction as C's `_mm512_permutex2var_epi8`.

### 8. Fuse the entire hex pipeline into a single small method

The entire vector data path (load → compare → convert → compress → expand → combine → store) should be a single small method that C2 can inline and optimize holistically, similar to how the C code is a single function with ~40 operations.
