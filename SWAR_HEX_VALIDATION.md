# SWAR Hex Validation: Borrow-Canceling Paired Subtraction

## The Problem: Per-Byte Range Checks in a 64-Bit Register

SWAR (Sub-Word Parallelism) packs multiple byte-sized values into a single
64-bit register and operates on all of them using regular ALU instructions.
The challenge: 64-bit subtraction propagates borrows **across** byte
boundaries, unlike SIMD where each lane is isolated.

### The naive (broken) approach

To check if a byte `v` is a hex digit, we need:

```
('0' <= v <= '9') || ('a' <= v <= 'f') || ('A' <= v <= 'F')
```

A first attempt: `(v - '0') & 0x80` — this sets the high bit when
`v < '0'` (unsigned subtraction borrows). But 64-bit subtraction means
the borrow from byte `i` corrupts byte `i+1`.

**Example**: suppose bytes 0–1 are `0x20` (space, below '0') and `0x31` ('1'):

```
v    = 0x3100000000000020   (LE: byte0=0x20, byte1=0x31)
sub  = v - 0x3030303030303030
     = 0x00000000000000F0   ← byte 0 = 0xF0 (borrow), byte 1 = 0x00 (corrupted!)
```

Byte 1 should be `0x01` (0x31 − 0x30), but the borrow from byte 0
turns it into `0x00`. The high bit of byte 1 is now 0, incorrectly
suggesting `'1' < '0'`.

## The Solution: Paired Subtraction with Borrow Cancellation

The insight: compute **two** related quantities that both absorb the
**same** borrow, then XOR them. The borrow cancels out, leaving the
correct per-byte result.

### The trick

For a range `lo <= v < hi` (per byte), compute:

```
t = v + (0x80 - lo)    // high bit = 1 when v >= lo (per byte)
s = v + (0x80 - hi)    // high bit = 1 when v >= hi (per byte)
result = (t ^ s) & 0x8080808080808080L
```

At the single-byte level (no cross-byte borrow):

| Condition | t high bit | s high bit | t ^ s high bit |
|---|---|---|---|
| `v < lo` | 0 | 0 | 0 |
| `lo <= v < hi` | 1 | 0 | **1** |
| `hi <= v` | 1 | 1 | 0 |

The XOR gives 0x80 exactly for bytes in `[lo, hi)`. ✓

### Why borrow cancels

When a byte `i` produces a carry during `v + constant`, it adds 1 to
byte `i+1` in both `t` and `s`. Because both computations receive the
**same** incoming carry, the XOR at byte `i+1` cancels it:

```
((v_1 + c1_1 + carry_t0) ^ (v_1 + c2_1 + carry_s0)) & 0x80
```

If `carry_t0 == carry_s0` (both 0 or both 1), they cancel.

### When carries DIFFER (the caveat)

The carries can *differ* when:

```
carry in t byte 0:  v_0 + (0x80 - lo) >= 256   →  v_0 >= lo + 0x80
carry in s byte 0:  v_0 + (0x80 - hi) >= 256   →  v_0 >= hi + 0x80
```

For `lo + 0x80 <= v_0 < hi + 0x80`, `t` carries but `s` doesn't,
producing **differential carries** that DON'T cancel.

This means the trick is **not fully general** — it only works correctly
when the valid byte values avoid these carry thresholds.

### Why it works for hex digit validation

**Digit check** (`'0'(0x30) <= v < '9'+1(0x3A)`):

```
t = v + 0x50     // 0x80 - 0x30 = 0x50
s = v + 0x46     // 0x80 - 0x3A = 0x46
```

Carry thresholds:
- `t` carries: `v >= 0x30 + 0x80 = 0xB0`
- `s` carries: `v >= 0x3A + 0x80 = 0xBA`

Differential carry window: `[0xB0, 0xBA)`.

Valid hex digits: `0x30–0x39, 0x41–0x46, 0x61–0x66`.

All valid hex bytes are **well below** 0xB0. No carries occur in
either `t` or `s`. Borrow cancellation is irrelevant because there
are no borrows to cancel. ✓

**Letter check** (`'a'(0x61) <= v|0x20 < 'f'+1(0x67)`):

```
lowered = v | 0x20     // lowercase A–F → a–f
t = lowered + 0x1F     // 0x80 - 0x61 = 0x1F
s = lowered + 0x19     // 0x80 - 0x67 = 0x19
```

Carry thresholds:
- `t` carries: `lowered >= 0x61 + 0x80 = 0xE1`
- `s` carries: `lowered >= 0x67 + 0x80 = 0xE7`

After lowercasing, valid hex bytes are `0x30–0x39` (digits) and
`0x61–0x66` (letters). All are **well below** 0xE1. No carries
in either `t` or `s`. ✓

**What about garbage bytes?** The `compressed` value in our pipeline
packs hex bytes into the low N positions, with delimiter bytes (':',
'.') in the high positions. Delimiters are at most 0x3A, also below
any carry threshold. But other garbage byte values *could* trigger
differential carries in the positions we **don't check** — we only
validate the low N bytes, so this doesn't matter.

## Where Did This Technique Come From?

The paired-arithmetic trick for borrow-canceling SWAR range checks is
not new — it builds on ideas from:

1. **Hacker's Delight (Henry S. Warren, 2002)** — Chapter 2 discusses
   SWAR byte-level arithmetic and the zero-byte detection trick
   (`((x - 0x0101...) & ~x & 0x8080...)`) which uses paired subtraction
   to detect byte-level equality. This is the same borrow-canceling
   principle applied to `x ^ pattern` instead of `x - lo`.

2. **Wojciech Muła's SWAR tutorials** (2000s–2010s) — extensive
   collection of SWAR techniques for range checking, digit detection,
   and case conversion, including borrow-canceling patterns.

3. **simdjson / Lemire's work** — the `findByte` function in this
   codebase (`((xor - 0x0101...) & ~xor & 0x8080...)`) is the classic
   SWAR zero-byte detection trick, which is itself a borrow-canceling
   paired subtraction.

The specific formulation `(v + (0x80-lo)) ^ (v + (0x80-hi))` is a
straightforward derivation: to check `lo <= v < hi`, we express the
range check as two half-range checks (`v >= lo` and `v >= hi`), compute
both using `v + (0x80 - bound)` to set the high bit, and XOR to get
the range.

The critical insight about **why this specific case works** (valid hex
bytes being safely below the carry thresholds) was discovered during
implementation — the first version in the codebase history attempted
the naive `(x - lower) & 0x80` and failed (documented in ANALYSIS.md as
"Rejected: SWAR hex validation"). The borrow-canceling fix was derived
from the Hacker's Delight observation that XOR of paired subtractions
cancels borrows, combined with a careful analysis of the carry
thresholds for the specific byte values involved.

## Summary

| Property | Detail |
|---|---|
| Technique | Paired subtraction with XOR borrow cancellation |
| Formula | `(v + (0x80-lo)) ^ (v + (0x80-hi))` |
| Borrow-free? | Yes — valid hex values avoid carry thresholds |
| General? | **No** — differential carries occur for values in `[lo+0x80, hi+0x80)` |
| Ops per 8 bytes | 4 ALU + 1 AND + 1 XOR (vs 8 branches for per-byte) |
| Correctness | Verified against all 9 benchmark + 7 invalid addresses |

The technique is best described as **"domain-restricted borrow-canceling
SWAR range checking"** — it applies the standard paired-arithmetic trick
from Hacker's Delight within the specific domain where carry thresholds
don't overlap with valid input values.
