# Project Memory

## Recent Iterations

### Iteration 17 (current HEAD: `032177b`)
- **Arithmetic hex conversion** on hot/mixed paths: replaces LUT `vpermb` (port 5) with `vpandb`+`vpaddb`+`vpcmpb`+`vpblendmb` (1 port 5)
  - `hexNibblesArithmetic`: `v.and(0x0F)` → nibble, `v.compare(GE,'A')` → letter mask, `nibble + 9` → letter value
  - Hybrid: arithmetic on hot/mixed (no validation needed), LUT kept for cold path (needs -1 sentinel for validate)
- **Result**: Hot path 112.0→118.4 M/s (**+5.7%**), cold path 51.2 M/s (unchanged)

### Iteration 16 (`7391abc`)
- **Precomputed `HOT_COMPRESS_MASK`**: 39-byte nonDelim precomputed (colon positions fixed: 4,9,14,19,24,29,34)
- **Precomputed `PAIR_MASK`**: `VectorMask.fromLong(SPECIES, 0x55555555L)` for pairNibbles compress
- **Deferred `nonDelim` computation** to mixed-span/cold paths only
- **Result**: Hot path 108.2→112.0 M/s (**+3.5%**), cold path 51.8 M/s (unchanged)

### Iteration 15 (`be8e839`)
- Three optimizations to `Ipv6ParserVector.java`:
  1. **Merged delimiter detection**: Single vector load for both `':'` and `'.'` detection in single-vector path; reuses loaded vector for hex conversion (eliminates redundant `findDelimiters` calls and duplicate `fromArray`)
  2. **Precomputed MASK_39 and MASK_16**: Static final fields replacing `SPECIES.indexInRange(0, 39/16)` — avoids allocation + computation on hot path
  3. **nonDelim mask for hot path**: Derives compresses mask from delimiter bits directly instead of `r.compare(GE, 0).toLong()` check — avoids compare + kmovq on hot path
- Bug fix: `ipv4Suffix` now validates octets > 255 (returns null for `::ffff:192.168.0.256`)
- Added `junit-jupiter-params` dependency for `@MethodSource` tests
- **Result**: 50→108 M/s (+116%) on hot path 39-byte full form (`2001:0db8:0000:0000:0000:0000:0000:0001`)

### Iteration 14 (`af511f0`)
- Profiled hot path on precision (i9-11950H): IPC 4.95, 371 inst/op, 75 cyc/op, 61.8 M/s
- Full profile analysis captured in PERF_PROFILE.md
- Finding: No single bottleneck — gains must come from instruction count reduction

### Iteration 13: Short-vector pairing (`6882bab`)
- Replaced even/odd 256-bit rearrange with 128-bit short-vector zipping
- Mixed-span throughput: 34.3→38.6 M/s (+12.5%)

## Key Files
- `src/main/java/ipv6parse/Ipv6ParserVector.java` — main optimized implementation
- `src/test/java/ipv6parse/Ipv6ParserVectorTest.java` — 29 parameterized tests
- `pom.xml` — requires `junit-jupiter:5.11.0` and `junit-jupiter-params:5.11.0` for tests
- `ANALYSIS.md` — iteration-by-iteration analysis
- `PERF_PROFILE.md` — hot path performance profile
- `VECTOR_PERF_ANALYSIS.md` — load port bottleneck analysis
- `NEXT_IMPROVEMENTS.md` — future optimization candidates

## Benchmark Command
```bash
java --add-modules jdk.incubator.vector -jar target/ipv6-parse-bench-1.0.jar '\.vector$' -p address='2001:0db8:0000:0000:0000:0000:0000:0001' -f 0 -wi 10 -i 10 -r 3
```

## Build (no tests)
```bash
mvn package -q -DskipTests
```

## Run tests
```bash
mvn test -q
```

## Performance (precision: i9-11950H @ 2.6 GHz)
| Version | Hot path | ::1 (cold) | Cumulative gain |
|---------|----------|-----------|----------|
| Iteration 14 | 50 M/s | 33 M/s | Baseline |
| Iteration 15 | 108 M/s | 50 M/s | +116% hot / +52% cold |
| Iteration 16 | 112 M/s | 52 M/s | +3.5% hot |
| **Iteration 17** | **118 M/s** | **51 M/s** | **+136% from baseline** |

## Key Design Decisions
- `hexNibblesArithmetic` for hot/mixed paths, `hexNibblesLUT` for cold path (validation)
- `HOT_COMPRESS_MASK` precomputed for 39-byte (colon positions fixed at 4,9,14,19,24,29,34)
- `PAIR_MASK` = 0x55555555L, selects even byte lanes for compress after short-vector pairing
- LUT still needed for cold path (-1 sentinel for GE 0 validation)
