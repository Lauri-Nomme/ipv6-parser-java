# Performance Profile: Hot Path (39-byte All-Span-4)

**Profile date**: 2026-05-26
**Machine**: i9-11950H @ 2.6 GHz (Ice Lake, 64-byte AVX-512)
**Benchmark**: JMH via perfasm + perfnorm
**Address**: `2001:0db8:85a3:0000:0000:8a2e:0370:7334` (39 bytes)
**Throughput**: 61.8 M/s

---

## Key Metrics (per-parse)

| Metric | Hot Path | Mixed-Span |
|--------|----------|------------|
| Throughput | 61.8 M/s | 35.4 M/s |
| Instructions | 371.5 | 614.0 |
| Cycles | 75.0 | 122.0 |
| **IPC** | **4.95** | **5.03** |
| Branches | 45.1 | 85.6 |
| Branch misses | ~0 | ~0 |
| L1-dcache loads | 67.7 | 87.2 |
| L1-dcache stores | 30.7 | 40.3 |
| L1-dcache misses | 0.52 | 0.51 |

**IPC of 4.95 is near Ice Lake's theoretical max of 5.** This means no single execution-port bottleneck — the CPU is already near maximum efficiency. Further gains require reducing instruction count, not fixing stalls.

---

## Hottest Code Regions (perfasm)

### 94.9% of cycles in `Ipv6ParserVector.parse`

| Region | % Cycles | Dominant Operations |
|--------|----------|-------------------|
| 1 | **43.6%** | `checkMaskFromIndexSize` + `fromArray`, LUT hex conversion (`compare`+`blend`+`sub`+`rearrange`), `intoArray` store setup |
| 2 | **30.8%** | Two `vpcompressb` calls, `reinterpretAsShorts`, short-vector pairing (and/lshr/and/mul/or), `indexPartiallyInUpperRange` |
| 3 | **13.3%** | `max` + `i2l` (indexInRange / checkIndex0 bounds checking for intoArray) |
| 4 | 4.3% | Validation check, len-nc computation |
| 5 | 2.8% | Remainder |

---

## Vector API Overhead

By counting annotation references in perfasm output:

| Component | Annotation Count | Est. % |
|-----------|-----------------|--------|
| `intoArray` + `intoArray0Template` (store) | 103 | ~28% |
| `checkMaskFromIndexSize` + `checkIndexByLane` + `checkIndex0` (range checks) | 50 | ~13% |
| `broadcast` (constant setup for compares) | 33 | ~9% |
| `toLong` (mask extraction) | 27 | ~7% |
| `fromArray` (load) | 23 | ~6% |
| `compare` (vector cmp) | 12 | ~3% |
| `rearrange` (vpermb LUT) | 10 | ~3% |
| `blend` | 4 | ~1% |
| `compressExpandOp` (vpcompressb) | 4 | ~1% |
| `convert` (reinterpretAsShorts) | 2 | <1% |
| **Our parse logic** (findDelimiters, parse lines) | 35 | ~9% |

---

## Interpretation

### Where cycles go:

1. **Vector API range checking (~17%)**: `checkMaskFromIndexSize`, `checkIndexByLane`, `checkIndex0` are called for every `fromArray` (line 98) and `intoArray` (line 112). Each does: mask AND, max, broadcast, i2l, bounds check. These are safety guards absent in C.

2. **Vector load + store (~34%)**: `fromArray` (input load at line 98), `intoArray0` (output store at line 112). The store setup is particularly heavy: mask construction, range checks, length checks around a single `vmovdqu8`.

3. **Hex conversion (~10%)**: `compare(GE,64)` + `sub(64)` + `blend` + `rearrange(LUT)`. The `rearrange` is `vpermb` (port 5), but the real overhead is `toShuffle` index validation.

4. **Compress + pair (~32%)**: `compress` (vpcompressb), `reinterpretAsShorts`, short-vector arithmetic, `compress` (second vpcompressb). Two port-5 ops.

### Mixed-span gap (35.4 vs 61.8 M/s)

Mixed-span uses 65% more instructions (614 vs 371) due to:
- `fillColonPositions`: tzcnt+blsr loop over 7 colons (~14 instr)
- `computeExpandMask`: per-segment span loop (~40 instr)
- `expand` (vpexpandb): port-5 consuming expand
- Additional branches and segment-loop overhead

---

## Bottleneck: No single bottleneck

IPC of 4.95 means the pipeline is saturated. Each of the remaining port-5 ops (vpermb, 2x vpcompressb, kmovq) accounts for roughly equal cycle time. The execution is balanced across all ports. This is the "good kind" of bottleneck — the CPU is running at full efficiency.

To go from 62 M/s to C's 71 M/s (15% gap), we need to reduce instruction count by ~15%, which means eliminating Vector API overhead.

---

## Updated Recommendations

| Priority | Idea | Est. Gain | Complexity |
|----------|------|-----------|------------|
| **1** | Merge delimiter detection (saves 1 load + 1 compare) | ~3% | Easy |
| **2** | Precomputed compress mask for len=39 (skips indexInRange) | ~5-8% | Easy |
| **3** | Skip hex validation on hot path (mask from delims) | ~3% | Easy |
| **4** | Eliminate final compress in pairNibbles | ~8%? | Hard |
| **5** | Direct hex-to-pair (bypass nibble step entirely) | ~15%? | Very Hard |

**Recommendation**: Implement #1 + #2 + #3 together (all easy, independent, low risk) for combined ~10-15% hot-path gain. Re-profile after.
