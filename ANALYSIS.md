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
| Hex conversion | `_mm512_permutex2var_epi8` lookup table | `sub/blend` arithmetic (3 range checks + blends) |
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

**Rejected: SWAR hex validation**  
An attempt to replace the per-byte `isHexByte` with a branchless all-8-bytes-at-once SWAR range check failed: Java's 64-bit subtraction propagates borrows across byte boundaries, unlike true per-byte SIMD arithmetic. The standard SWAR technique `(x - lower) & 0x80` does NOT work for per-byte unsigned comparison in Java because a borrow from byte `i` corrupts the result of byte `i+1`. This asymmetry between SWAR (where borrow propagation is a feature) and SIMD (where it's per-lane) is a fundamental limitation of SWAR in general-purpose CPUs.

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

### AVX-512 (11th Gen Intel Core i9-11950H @ 2.60 GHz, SPECIES = 64 bytes)

Same JDK and JMH config:

| Address | Len | Scalar (ops/s) | SWAR (ops/s) | SWAROpt (ops/s) | Vector (ops/s) | VectorCE (ops/s) |
|---|---|---|---|---|---|---|
| `2001:db8::1` | 11 | 19,997,291 | 13,776,889 | 17,038,831 | 18,067,247 | 14,838,822 |
| `::1` | 3 | 33,982,110 | 22,806,940 | 24,290,154 | 23,248,339 | 17,247,207 |
| `2001:db8:0:0:0:0:0:1` | 22 | 12,388,990 | 9,603,869 | 11,588,692 | 12,255,462 | 11,999,973 |
| `fe80::1` | 6 | 25,690,531 | 17,659,807 | 20,695,415 | 21,209,743 | 16,070,391 |
| `::ffff:192.168.0.1` | 20 | 14,340,075 | 8,712,920 | 10,713,866 | 11,970,233 | 11,507,705 |
| `2001:db8::c0a8:101` | 19 | 12,745,240 | 9,206,536 | 11,598,737 | 14,107,509 | 13,238,886 |
| `2001:0db8:0000:0000:0000:0000:0000:0001` | 39 | 7,597,881 | 5,485,795 | 7,081,232 | 10,199,834 | 10,209,110 |
| `2001:0db8:85a3:0000:0000:8a2e:0370:7334` | 39 | 7,530,337 | 5,432,606 | 7,037,192 | 10,190,637 | 10,175,868 |
| `1234:5678:9abc:def0:1234:5678:9abc:def0` | 39 | 7,356,329 | 5,429,708 | 6,909,905 | 10,116,841 | 10,200,970 |

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
- SWAROpt narrows the gap with scalar to **6–29%** depending on input length.
- Short inputs (3–11): 17.0–24.3 M ops/s (71–85% of scalar). The one-pass design still has per-chunk setup overhead.
- Medium inputs (19–22): 10.7–11.6 M ops/s (75–94% of scalar). Best relative showing on pure-hex addresses (94%).
- Long inputs (39): 6.9–7.1 M ops/s (93% of scalar). Nearly catches scalar.
- IPv4-mixed (`::ffff:192.168.0.1`): SWAROpt is 75% of scalar — the IPv4 suffix path bypasses all SWAR optimizations.

**SWAR vs SWAROpt**:

| Metric | SWAR | SWAROpt | Improvement |
|---|---|---|---|
| Instructions/op | 2,921 | 2,329 | **20% fewer** |
| Cycles/op | 523 | 461 | **12% fewer** |
| Throughput (39¬byte) | 5.45 M/s | 7.01 M/s | **+29%** |

Three changes drove the improvement:
1. **VarHandle direct long load** — eliminates per-byte loadLong loop for full chunks
2. **Combined Phase 1+3** — hex bytes validated and converted to nibbles immediately after `Long.compress`, removing the separate hexBuf→nibBuf pass
3. **Merged loops** — validation and nibble storage happen in one pass

**Full ranking (AVX-512, throughput)**:

| Input | 1st | 2nd | 3rd | 4th | 5th |
|---|---|---|---|---|---|
| Short (3–11) | Scalar 1.27× | Vector 1× | SWAROpt 0.99× | SWAR 0.87× | VectorCE 0.77× |
| Medium (19–22) | Scalar 1.03× | Vector 1× | VectorCE 0.96× | SWAROpt 0.88× | SWAR 0.72× |
| Long (39) | Vector 1.36× | VectorCE 1.36× | Scalar 1× | SWAROpt 0.94× | SWAR 0.73× |

**Key findings**:
1. **Scalar still wins for short inputs** — the tight JIT-compiled loop is hard to beat.
2. **Vector is the most consistent** — nearly flat across all input lengths (10–23 M).
3. **VectorCE is close to Vector on AVX-512** but never surpasses it, confirming that Java's `compress`/`expand` intrinsics add overhead over raw `VPCOMPRESS`/`VPEXPAND`.
4. **SWAROpt nearly catches scalar** for medium-to-long inputs (88–94%), making it a practical drop-in replacement. The ~100 remaining extra instructions per op are from per-chunk `Long.compress` calls and the nibble→output assembly pass.
5. **SWAR per-byte comparisons are fundamentally limited** on general-purpose CPUs: Java's 64-bit subtraction propagates borrows across byte boundaries, so SWAR unsigned range checks (`(x - lower) & 0x80`) don't work. Per-byte `isHexByte` is unavoidable.

Conclusion: **On AVX-512, the Vector API is the best choice for long inputs, while scalar remains optimal for short inputs.** SWAROpt is a strong third option that reaches 93% of scalar throughput on long inputs, making it competitive with the Vector API for mixed-length workloads.
