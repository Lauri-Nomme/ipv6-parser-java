# Project Memory

## Recent Iterations

### Iteration 15 (current HEAD: `be8e839`)
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
| Version | Hot path | ::1 (cold) | Note |
|---------|----------|-----------|------|
| Iteration 14 | 50 M/s | 33 M/s | Baseline |
| Iteration 15 | 108 M/s | 50 M/s | +116% hot / +52% cold |
