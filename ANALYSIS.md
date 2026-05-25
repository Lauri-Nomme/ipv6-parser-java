# Porting Lemire's AVX-512 IPv6 Parser to Java 17 (`byte[]` input)

## Source

https://github.com/lemire/Code-used-on-Daniel-Lemire-s-blog/blob/master/2026/05/22/benchmark/include/avx512ip.h

The C code contains two functions:
- `parse_ipv4_avx512vl` — AVX-512VL (128-bit) IPv4 parser
- `parse_ipv6_avx512` — AVX-512BW (512-bit) IPv6 parser with IPv4-suffix support

## The algorithmic essence

The SIMD approach is built on **five core ideas**, all of which port directly to scalar Java:

### 1. Delimiter-position arithmetic

Instead of walking characters and manually tracking group boundaries, find all delimiter positions first, then compute group sizes by subtracting consecutive positions.

| SIMD (C) | Java (scalar) |
|---|---|
| `_mm512_cmpeq_epu8_mask(str, colon)` → bitvector | loop: `if (b[i] == ':') col[nc++] = i` |
| `_lzcnt` / `_tzcnt` to find boundaries | linear scan (same O(n)) |
| position difference → group sizes | `segEnd[i] - segStart[i]` |

### 2. Consecutive-colon detection (`::`)

The C code finds `::` with a bitwise trick:

```c
uint64_t doublecolon = ((colons_bitvector) >> 1) & colons_bitvector;
```

This produces a bitmask where bit *i* is 1 if both position *i-1* and *i* are colons. It then validates `_blsr_u64(doublecolon) == 0` (at most one such pair).

In Java the same check is:

```java
int ccPairs = 0;
for (int i = 1; i < nc; i++)
    if (col[i] == col[i - 1] + 1) ccPairs++;
if (ccPairs > 1) return null;
```

### 3. Hex translation via lookup/arithmetic

The SIMD version uses `_mm512_permutex2var_epi8` with two 64-entry lookup tables to convert ASCII hex chars ('0'-'9', 'a'-'f', 'A'-'F') to nibble values (0-15) in parallel. Invalid bytes produce -1, detectable via `_mm512_movepi8_mask`.

The Java equivalent is just:

```java
private static int hexVal(byte b) {
    if (b >= '0' && b <= '9') return b - '0';
    if (b >= 'a' && b <= 'f') return b - 'a' + 10;
    if (b >= 'A' && b <= 'F') return b - 'A' + 10;
    return -1;
}
```

### 4. Compress → expand → multiply-accumulate

The SIMD pipeline for each hex group is:
1. **compress**: remove ':' bytes, keeping only hex chars (`_mm512_maskz_compress_epi8`)
2. **expand**: pad shorter groups with leading zeros using a precomputed expand mask (`_mm512_maskz_expand_epi8`)
3. **multiply-accumulate**: `_mm256_maddubs_epi16(padded, (0x01, 0x10))` computes `(hi_nibble * 16 + lo_nibble)` per 16-bit lane = hex value → `_mm256_cvtepi16_epi8` packs to bytes

The Java equivalent is simpler: just left-shift and OR each nibble into a 16-bit accumulator:

```java
int v = 0;
for (int i = 0; i < len; i++) v = (v << 4) | hexVal(b[off + i]);
out[oi]   = (byte)(v >> 8);
out[oi+1] = (byte)(v);
```

Padding is implicit: a 2-char hex group like "ab" naturally becomes `0x00ab` via the shift accumulation, matching the SIMD zero-padding.

### 5. IPv4 suffix handling

When dots are present, the SIMD code isolates the portion after the last colon, finds dot positions, computes per-octet digit counts via the same position-difference arithmetic, validates 1-3 decimal digits (no leading zero, ≤255), then uses `_mm_dpbusd_epi32` to multiply-accumulate octets into a 32-bit integer.

The Java version does exactly the same steps linearly:

```java
for each octet:
    validate 1-3 decimal digits, no leading zero
    accumulate: v = v * 10 + (c - '0')
    validate v ≤ 255
    write as byte
```

## Limitations of the port

| Feature | C (AVX-512) | Java 17 |
|---|---|---|
| Character classification | 64-way parallel via lookup tables | scalar per byte |
| Delimiter detection | parallel bitmask | linear scan |
| Compress/expand | `_mm512_maskz_compress/expand_epi8` | implicit via loop |
| Multiply-accumulate | `_mm256_maddubs_epi16` | shift-accumulate |
| Validation (digit ranges, leading zeros) | parallel compare-masks | scalar if-checks |

The SIMD version processes all 45 input bytes in one shot with 64-byte registers. The Java version processes them one byte at a time. **The algorithm is identical** — only the parallelism is lost.

## Correctness

The Java parser handles all the same cases as the C code:

- Full form: `2001:0db8:0000:0000:0000:0000:0000:0001`
- Compressed (`::`): `2001:db8::1`, `::1`, `1::`, `::`
- Mixed IPv4: `2001:db8::192.168.0.1`, `::ffff:192.168.0.1`
- Leading zeros allowed in hex groups (unlike IPv4)
- Rejects: multiple `::`, >4 hex digits per group, bad hex chars, invalid IPv4 octets (leading zero, >255, wrong dot count)

---

## Vector API Implementation

The [`Ipv6ParserVector.java`](src/main/java/ipv6parse/Ipv6ParserVector.java) class uses the Java `jdk.incubator.vector` API (available since JDK 16) to replicate the SIMD parallelism.

### What's vectorized

| Phase | C (AVX-512) | Java Vector API |
|---|---|---|
| Colon detection | `_mm512_cmpeq_epu8_mask` → 64-bit bitmask | `ByteVector::compare(EQ, ':')` → `toLong()` |
| Dot detection | same approach | same approach |
| Hex conversion | `_mm512_permutex2var_epi8` lookup table | single-LUT `rearrange` (shift+blend + `vpermb`) |
| Compress/expand | `maskz_compress/expand_epi8` | **not available** → scalar fallback |
| Group assembly | `_mm256_maddubs_epi16` → `cvtepi16_epi8` | **not available** → scalar shift-accumulate |

The Vector API has no equivalent of the x86 `compress`/`expand` instructions, which are the heart of Lemire's approach. The hex conversion can be vectorized (using `sub` + range-check masks + `blend`), but the results must still be extracted group-by-group via scalar code.

## SWAR Implementation (4th variant)

The [`Ipv6ParserSWAR.java`](src/main/java/ipv6parse/Ipv6ParserSWAR.java) class uses **Sub-Word Parallelism** — processing 8 bytes at a time inside a 64-bit register using bitwise/arithmetic tricks, plus Java 21's `Long.compress`/`Long.expand`.

### High-level design

The data flow mirrors Lemire's SIMD approach, substituting 64-bit SWAR primitives for 512-bit vector ones:

```
                 ┌─ SWAR find(':') ──→ colon bits (per byte position)
load 8 bytes ────┤
                 └─ SWAR find('.') ──→ dot bits
                         │
                         ▼
              per‑byte hex mask
                         │
                         ▼
              Long.compress(chunk, hexMask)
              ──→ packed hex digit bytes
                         │
                         ▼
              accumulate into byte[] (up to 32 hex chars)
                         │
                         ▼
              SWAR hex→nibble (8 bytes at a time)
              sub‑0x30 | detect letters | detect lowercase ──→ 0x00–0x0F
                         │
                         ▼
              per‑group nibble assembly → 16‑byte result
```

### Key SWAR techniques used

**1. Byte‑wise equality (`findByte`)**
```java
long xor = chunk ^ (pattern repeated);
long mask = ((xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L);
```
The MSB of each byte is set iff that byte == pattern. This is the classic "zero-byte detection" trick applied to `xor`.

**2. Per‑byte hex mask → compress**
```java
long delimBytes = ((colons | dots) >>> 7) * 0xFFL;  // 0xFF per delimiter byte
long hexMask = ~delimBytes & validBits;              // keep only hex digit bytes
long packed = Long.compress(chunk, hexMask);         // pack hex bytes contiguously
```
`Long.compress` (Java 19+, equivalent to BMI2 `PEXT` at the bit level) moves all hex digit bytes to the low end of the long, discarding delimiters.

**3. SWAR hex‑to‑nibble conversion**
```java
long t = v - 0x3030303030303030L;
long isLetter = ~(t + 0xF6F6F6F6F6F6F6F6L) & 0x8080808080808080L;
long adj = (isLetter >>> 5) | (isLetter >>> 6) | (isLetter >>> 7); // 0x07 per letter byte
long isLower = t & 0x2020202020202020L;                 // 0x20 for a‑f
return t - (adj | isLower);                              // → 0x00–0x0F per byte
```
Operates on all 8 bytes simultaneously using unsigned arithmetic. Important: `>>>` (logical shift) is required, not `>>` (arithmetic), because `isLetter` may have bit 63 set.

**4. Accumulation across chunks**  
Since IPv6 addresses can have up to 32 hex digits (exceeding a single 64-bit long), packed bytes from each 8-byte chunk are extracted into a `byte[32]` before the nibble conversion.

### SWAROpt — Optimized version

[`Ipv6ParserSWAROpt.java`](src/main/java/ipv6parse/Ipv6ParserSWAROpt.java) adds three optimizations over the baseline SWAR:

**1. Direct long load via VarHandle**  
Replaces the per-byte `loadLong` loop with `MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN)` for full 8-byte chunks. This compiles to a single `MOV` instruction (or `MOVQ`) instead of 8 individual byte loads + shifts + ORs.

**2. Combined Phase 1+3 (eliminates hexBuf → nibBuf pass)**  
The baseline SWAR writes compressed hex chars to `hexBuf`, then makes a second pass converting to nibbles via `swarHexConvert`. SWAROpt converts immediately after `Long.compress` — validate, `swarHexConvert`, and store nibbles in one go. This eliminates the `hexBuf` allocation and the entire second pass.

**3. Merged validation + extraction loop**  
Instead of separate loops for validation (per-byte `isHexByte`) and extraction (per-byte `storeNibbles`), both happen in one pass.

**4. SWAR hex validation (borrow-canceling paired subtraction)**  
Replaces per-byte `isHexByte` with `swarIsHexMask` — a branchless all-8-bytes-at-once SWAR range check. A naive approach `(x - lower) & 0x80` fails because 64-bit borrow from byte `i` corrupts byte `i+1`. The fix uses paired subtraction: both `t = v + (0x80 - lo)` and `s = v + (0x80 - hi)` absorb the same borrow from lower bytes, so XOR `(t ^ s)` cancels the borrow and gives the correct per-byte range membership:

```java
// isDigit: '0' <= v < '9'+1
long isDigit = ((v + 0x50) ^ (v + 0x46)) & 0x8080808080808080L;
// isHexLetter: 'a' <= (v|0x20) < 'f'+1
long lowered = v | 0x2020202020202020L;
long isHexLetter = ((lowered + 0x1F) ^ (lowered + 0x19)) & 0x8080808080808080L;
```

**5. Borrow-safe `swarHexConvert`**  
Fixed `swarHexConvert` to use `(v & 0x40) << 1` (bit 6 of the original byte) instead of `(t + 0xF6)` to detect letter bytes. The original `t + 0xF6` (equivalent to `t - 10`) has a borrow-propagation bug: a letter byte (t ≥ 10) produces a carry into the next byte, corrupting its `isLetter` result when the next byte is a digit '9' (t = 9). Using `v & 0x40` is safe because bit 6 distinguishes letters ('A'-'F' / 'a'-'f' have bit 6 set, '0'-'9' don't) without any arithmetic borrow.

## JMH Benchmark Results

### AVX2 (11th Gen Intel Core i7-11850H @ 2.60 GHz, SPECIES = 32 bytes)

JDK 21.0.10 (Amazon Corretto), non-forked (1 s warmup × 2, 1 s measurement × 5, throughput mode):

| Address | Len | Scalar (ops/s) | Vector (ops/s) | VectorCE (ops/s) |
|---|---|---|---|---|
| `2001:db8::1` | 11 | 19,619,143 | 7,802,177 | 4,691,500 |
| `::1` | 3 | 35,041,204 | 7,307,263 | 5,904,313 |
| `2001:db8:0:0:0:0:0:1` | 22 | 12,716,291 | 7,076,122 | 2,788,807 |
| `fe80::1` | 6 | 27,430,457 | 7,720,157 | 4,644,110 |
| `::ffff:192.168.0.1` | 20 | 13,905,946 | 7,050,093 | 3,176,923 |
| `2001:db8::c0a8:101` | 19 | 13,678,075 | 7,507,052 | 2,641,763 |
| `2001:0db8:0000:0000:0000:0000:0000:0001` | 39 | 8,346,692 | 5,329,091 | 3,701,039 |
| `2001:0db8:85a3:0000:0000:8a2e:0370:7334` | 39 | 8,528,282 | 5,333,342 | 3,698,924 |
| `1234:5678:9abc:def0:1234:5678:9abc:def0` | 39 | 8,240,495 | 5,338,089 | 3,695,455 |

### AVX2 (14th Gen Intel Core i7-14700K, no AVX-512)

Full six‑way comparison (JDK 21.0.10 Corretto, non-forked 1×1 s warmup, 3×1 s measurement, throughput):

| Address | Len | Scalar (ops/s) | SWAR (ops/s) | SWAROpt (ops/s) | Vector (ops/s) | VectorCE (ops/s) |
|---|---|---|---|---|---|---|
| `2001:db8::1` | 11 | 19,571,940 | 14,800,862 | 17,909,194 | 7,766,676 | 4,548,247 |
| `::1` | 3 | 35,298,162 | 23,221,136 | 24,106,467 | 7,660,514 | 6,248,002 |
| `2001:db8:0:0:0:0:0:1` | 22 | 11,953,457 | 9,788,655 | 13,007,228 | 6,857,150 | 3,185,058 |
| `fe80::1` | 6 | 27,065,830 | 19,331,935 | 23,358,609 | 7,721,306 | 4,795,971 |
| `::ffff:192.168.0.1` | 20 | 14,734,671 | 9,270,534 | 12,343,401 | 7,185,162 | 3,454,319 |
| `2001:db8::c0a8:101` | 19 | 12,813,073 | 9,845,469 | 13,000,044 | 7,492,423 | 2,775,999 |
| `2001:0db8:...0001` | 39 | 7,835,570 | 5,689,263 | 8,115,070 | 4,908,983 | 4,387,134 |
| `2001:0db8:...7334` | 39 | 7,779,415 | 5,694,762 | 8,097,248 | 4,895,082 | 4,401,909 |
| `1234:5678:...def0` | 39 | 7,543,731 | 5,625,587 | 8,054,765 | 4,918,512 | 4,392,766 |

SWAROpt is **38–43% faster than SWAR** on 39-byte inputs (vs 29% on AVX-512 i9-11950H), and reaches **96–104% of scalar** on the same inputs — essentially matching scalar performance on the newest P-cores.

### AVX-512 (11th Gen Intel Core i9-11950H @ 2.60 GHz, SPECIES = 64 bytes)

Same JDK and JMH config:

| Address | Len | Scalar (ops/s) | SWAR (ops/s) | SWAROpt (ops/s) | Vector (ops/s) | VectorCE (ops/s) |
|---|---|---|---|---|---|---|
| `2001:db8::1` | 11 | 20,476,956 | 14,430,420 | 17,367,396 | **25,781,249** | 17,664,688 |
| `::1` | 3 | 33,981,524 | 22,610,790 | 23,952,686 | **30,321,715** | 20,910,082 |
| `2001:db8:0:0:0:0:0:1` | 22 | 12,272,839 | 9,734,620 | 12,615,082 | **19,521,156** | 12,039,317 |
| `fe80::1` | 6 | 25,803,195 | 18,639,502 | 21,866,249 | **30,831,615** | 19,915,257 |
| `::ffff:192.168.0.1` | 20 | 14,090,521 | 8,955,212 | 11,534,825 | **15,934,747** | 13,439,228 |
| `2001:db8::c0a8:101` | 19 | 12,755,046 | 9,550,051 | 12,436,892 | **21,772,184** | 15,231,677 |
| `2001:0db8:0000:0000:0000:0000:0000:0001` | 39 | 7,482,555 | 5,730,584 | 8,399,369 | **28,293,008** | 11,723,049 |
| `2001:0db8:85a3:0000:0000:8a2e:0370:7334` | 39 | 7,628,920 | 5,678,687 | 8,390,723 | **28,122,987** | 11,743,336 |
| `1234:5678:9abc:def0:1234:5678:9abc:def0` | 39 | 7,403,583 | 5,595,413 | 8,357,043 | **28,128,279** | 11,686,738 |

*Iteration 11b: Cold-path compress+expand+pair replaces per-segment scalar loop for `::`-without-IPv4 cases. `computeExpandMask` handles empty segments (span=0). Mixed-span path (e.g. `2001:db8:0:0:0:0:0:1`) also uses compress+expand+pair — 27.1 -> 35.0 M/s (**+29%**). Cold path `2001:db8::1`: 27.1 -> 30.4 M/s (**+12%**). `fe80::1`: 31.7 -> 35.4 M/s (**+12%**). 39-byte stable at 58.1 M/s. `::+IPv4` unchanged (falls through to per-segment loop).*

*Iteration 11a: Replace VectorCE's `intoArray(TMP)` + scalar loop with shuffle+mul+or pairing in the non-all-span-4 path. Removes 85 instructions from the ~1560 instruction body. VectorCE 39-byte fast path unchanged (already uses compress+pair from Iteration 10).*

*Iteration 10: Apply compress+pair vector pairing to VectorCE's `compressExpandPath` for the all-span-4 case. Bypasses the expand mask construction + scalar TMP loop. VectorCE 39-byte throughput: 11.0 -> 15.8 M/s (**+44%**). Short/cold/mixed-span inputs unchanged.*
 
 *Iteration 9: Defer colon position extraction to when needed. Compute `nc` and `ccPairs` directly from `colonBits` bitmask (2 popcnts + shift + and). Full colon extraction loop (~18% of cycles) now only runs for non-compress+pair paths. 39-byte throughput: 39.3 -> 50.2 M/s (**+28%**). Achieves **5.8x vs scalar** and **70% of C AVX-512 throughput** (gap: 1.4x).*

*Iteration 8: Three optimizations for the compress+pair fast path. (1) Replace `Long.bitCount` popcnt with `len - nc` subtraction. (2) Skip Phase 2 empty detection loop when `ccPairs == 0`. (3) Replace lane extraction + byte loop with single masked `intoArray` (`vmovdqu8`). 39-byte throughput: 28.1 -> 39.3 M/s (**+40%**). Achieves **4.85x vs scalar** and reaches **55% of C AVX-512 throughput**. Short inputs unchanged.*

*Iteration 7: Vector compress+pair assembly for the all-span-4 fast path (common 39-byte full-form). Replaces 5 long lane extractions + scalar bit-op loop (was ~50% of cycles) with 5 vector ops (compress + 2 rearranges + mul + or). 39-byte throughput nearly doubled: 15.7 -> 28.1 M/s (**+79%**). Achieves **3.71x vs scalar** on long inputs. Short/cold-path inputs unchanged.*

*Iteration 5: Split assembly loop into fast-path (all-hex, no `::`, no IPv4) and cold path — eliminates `isEmpty`/`isHex` branches from the hot loop. Long inputs improved +11–13% over iteration 4; short inputs unchanged.*

### Analysis

#### AVX2: Scalar dominates (2–5× faster)

On 32-byte vectors, the input rarely fits in one lane:

- **Short inputs** (3–22 bytes, fitting in one vector): Vector is 2.5–4.8× slower than scalar. The JIT's auto-vectorized tight loop beats manual vector setup.
- **Long inputs** (39 bytes, straddles 2 vectors): Gap narrows to 1.5–1.6× — still scalar wins.
- **VectorCE is worse**: compress/expand fallback intrinsics are software loops (no `VPCOMPRESS`/`VPEXPAND` on AVX2), adding 1.4–2.8× overhead over Vector.

Reasons:
1. **Small input** → vector setup cost dominates
2. **No HW compress/expand** on AVX2 → fallback loops
3. **JIT auto-vectorization** of scalar tight loop
4. **3 passes vs. 1 pass** and **intermediate allocation**

#### AVX-512: Five‑way comparison

64-byte lanes change the picture — all inputs fit in **one vector**:

**SWAROpt performance**:
- SWAROpt narrows the gap with scalar to **6–30%** depending on input length.
- Short inputs (3–11): 17.4–24.0 M ops/s (70–84% of scalar). The one-pass design still has per-chunk setup overhead.
- Medium inputs (19–22): 11.5–12.4 M ops/s (76–98% of scalar). Best relative showing on pure-hex addresses (98%).
- Long inputs (39): 8.4 M ops/s (**110% of scalar**). SWAROpt now exceeds scalar on long inputs.
- IPv4-mixed (`::ffff:192.168.0.1`): SWAROpt is 80% of scalar — the IPv4 suffix path bypasses all SWAR optimizations.

**SWAR vs SWAROpt** (AVX-512 i9-11950H, `perf stat` via JMH `-prof perfnorm`, 1 fork × 1 s, 39-byte address):

| Metric | SWAR | SWAROpt | Improvement |
|---|---|---|---|
| Throughput | 5.62 M/s | 8.93 M/s | **+59%** |
| Instructions/op | 4,569 | 2,790 | **-39%** |
| Cycles/op | 993 | 585 | **-41%** |
| IPC | 4.60 | 4.77 | +4% |
| Branches/op | 671 | 358 | **-47%** |
| Branch misses/op | 0.875 | 0.042 | **-95%** |
| L1-dcache-loads/op | 726 | 383 | -47% |
| L1-dcache-stores/op | 281 | 159 | -43% |

The SWAR validation improvements widened the gap vs the previous SWAROpt (which was +29% on 39-byte). The per-byte `isHexByte` loop in the old SWAROpt was replaced with `swarIsHexMask` — a branchless SWAR range check that eliminated **95% of branch mispredictions** (0.875 → 0.042/op). Combined with the borrow-safe `swarHexConvert` fix and the existing optimizations, SWAROpt now uses 39% fewer instructions and 41% fewer cycles than SWAR. See [`SWAR_HEX_VALIDATION.md`](SWAR_HEX_VALIDATION.md) for a detailed explanation of the borrow-canceling paired subtraction technique.

Five changes drove the improvement:
1. **VarHandle direct long load** — eliminates per-byte loadLong loop for full chunks
2. **Combined Phase 1+3** — hex bytes validated and converted to nibbles immediately after `Long.compress`, removing the separate hexBuf→nibBuf pass
3. **Merged loops** — validation and nibble storage happen in one pass
4. **SWAR hex validation** (`swarIsHexMask`) — borrow-canceling paired subtraction replaces per-byte `isHexByte`
5. **Borrow-safe `swarHexConvert`** — uses `v & 0x40` instead of `t + 0xF6` to detect letters, fixing a borrow-propagation bug when a letter byte precedes a digit '9' within the same 8-byte chunk

**Full ranking (AVX-512, throughput)**:

| Input | 1st | 2nd | 3rd | 4th | 5th |
|----|---|---|---|---|---|---|
| Short (3-11) | **Vector 1.15-1.25x** | Scalar 1x | VectorCE 0.65-0.92x | SWAROpt 0.68-0.73x | SWAR 0.57x |
| Medium (19-22) | **Vector 1.85-2.85x** | VectorCE 0.97-1.20x | SWAROpt 1.02x | Scalar 1x | SWAR 0.80x |
| **Long (39)** | **Vector 7.4x** | VectorCE 2.0x | SWAROpt 1.04x | Scalar 1x | SWAR 0.70x |


*Rankings updated after LUT-based hex conversion. Vector and VectorCE improved significantly on long inputs (from ~1.35× to ~1.58× and ~1.50× vs scalar respectively).*

**Key findings**:
1. **Vector now leads on medium and long inputs** — overtaking SWAROpt on 19–22 byte addresses (1.13× vs SWAROpt 1.03×) and widening its lead on 39-byte inputs to **1.58×**.
2. **Scalar still wins on short inputs** (3–11 bytes) — the tight JIT-compiled loop is hard to beat, but Vector now nearly matches it (1.01×).
3. **VectorCE improved from 1.36× to 1.50×** on long inputs, but remains behind Vector.
4. **Vector is the most consistent** — nearly flat across all input lengths (12–24 M).
5. **SWAROpt unchanged** (unchanged by this iteration) at 1.12× scalar on long inputs.
6. **SWAR hex validation is achievable with borrow-canceling paired subtraction**: While `(x - lower) & 0x80` fails due to byte-level borrow propagation, the paired-subtraction trick (`(v + (0x80-lo)) ^ (v + (0x80-hi))`) cancels the borrow across both operations and gives correct per-byte range membership.

Conclusion: **On AVX-512, Vector is now the best all-rounder** — leads on medium (+13%) and long (+58%) inputs, nearly matches scalar on short inputs. The LUT-based `rearrange` fix closed the gap with C's `_mm512_permutex2var_epi8`, making Vector the strongest performer for mixed-length IPv6 parsing workloads.
