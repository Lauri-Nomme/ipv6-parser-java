package ipv6parse;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

public class Ipv6ParserVector {

    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    static final int SL = SPECIES.length();
    // Single 64-entry LUT — we shift hi-bytes down before shuffling so
    // indices are always in [0, 64).  Digits stay at their ASCII positions
    // ('0'-'9' → 48-57), while 'A'-'F' (65-70) → 1-6 and 'a'-'f' (97-102) → 33-38.
    private static final ByteVector LUT;
    // Shuffles for pairing 32 contiguous nibbles into 16 bytes
    static final VectorShuffle<Byte> SHUFFLE_EVEN;
    static final VectorShuffle<Byte> SHUFFLE_ODD;
    // reusable buffer for hex nibble output (single-threaded use)
    static final byte[] HEX_BUF = new byte[45];
    static {
        byte[] lut = new byte[64];
        for (int i = 0; i < 64; i++) lut[i] = -1;
        for (int i = '0'; i <= '9'; i++) lut[i] = (byte)(i - '0');
        for (int i = 'A'; i <= 'F'; i++) lut[i - 64] = (byte)(i - 'A' + 10);
        for (int i = 'a'; i <= 'f'; i++) lut[i - 64] = (byte)(i - 'a' + 10);
        LUT = ByteVector.fromArray(SPECIES, lut, 0);
        int[] even = new int[64];
        int[] odd  = new int[64];
        for (int i = 0; i < 16; i++) {
            even[i] = i * 2;
            odd[i]  = i * 2 + 1;
        }
        SHUFFLE_EVEN = VectorShuffle.fromArray(SPECIES, even, 0);
        SHUFFLE_ODD  = VectorShuffle.fromArray(SPECIES, odd, 0);
    }

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        // ---- Phase 1: vectorized delimiter detection -----------------
        long colonBits = findDelimiters(input, off, len, (byte) ':');
        long dotBits   = findDelimiters(input, off, len, (byte) '.');

        int nc = Long.bitCount(colonBits);
        if (nc == 0) return null;
        int ccPairs = Long.bitCount(colonBits & (colonBits >> 1));
        if (ccPairs > 1) return null;

        boolean hasDot = dotBits != 0;
        int dotCount = Long.bitCount(dotBits);

        int[] col = new int[8];

        // ---- Phase 2: detect empty segments & validate ----------------
        int segs = nc + 1;
        int emptyCount = 0;
        boolean emptiesConsecutive = true;
        if (ccPairs > 0) {
            fillColonPositions(colonBits, col);
            int lastEmptyIdx = -2;
            for (int i = 0; i <= nc; i++) {
                int start = i == 0 ? 0 : col[i - 1] + 1;
                int end = i == nc ? len : col[i];
                if (start == end) {
                    emptyCount++;
                    if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1)
                        emptiesConsecutive = false;
                    lastEmptyIdx = i;
                }
            }
            if (!emptiesConsecutive) return null;
        }
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        // ---- Phase 5+6: validate, convert & assemble output ----------
        byte[] out = new byte[16];
        long delims = colonBits | dotBits;

        if (len <= SL) {
            // Single-vector fast path: keep nibbles in register longs
            VectorMask<Byte> lm = SPECIES.indexInRange(0, len);
            ByteVector v = ByteVector.fromArray(SPECIES, input, off, lm);
            VectorMask<Byte> isHi = v.compare(VectorOperators.GE, (byte) 64);
            ByteVector idx = v.blend(v.sub((byte) 64), isHi);
            ByteVector r = LUT.rearrange(idx.toShuffle());

            VectorMask<Byte> keep = r.compare(VectorOperators.GE, (byte) 0);
            if (((~keep.toLong()) & ((1L << len) - 1)) != delims) return null;

            if (emptyCount == 0 && !hasDot) {
                // Hot path: all hex segments, no ::, no IPv4
                // len - nc = hex chars (no dots on this path), avoids popcnt
                if (len - nc == hexGroups * 4) {
                    // All segments span exactly 4 chars — vector compress+pair
                    ByteVector hexNibs = r.compress(keep);
                    ByteVector evens = hexNibs.rearrange(SHUFFLE_EVEN);
                    ByteVector odds  = hexNibs.rearrange(SHUFFLE_ODD);
                    ByteVector paired = evens.mul((byte) 16).or(odds);
                    paired.intoArray(out, 0, SPECIES.indexInRange(0, 16));
                    return out;
                }
                // Mixed-span fast path: compress + expand(per-segment pad) + pair
                // Replaces per-segment scalar extraction with vector compress+expand+pair
                long expandBits = computeExpandMask(colonBits, len, nc, segs, pad);
                if (expandBits == -1L) return null; // invalid span
                ByteVector hexNibs = r.compress(keep);
                ByteVector padded = hexNibs.expand(VectorMask.fromLong(SPECIES, expandBits));
                ByteVector evens = padded.rearrange(SHUFFLE_EVEN);
                ByteVector odds  = padded.rearrange(SHUFFLE_ODD);
                ByteVector paired = evens.mul((byte) 16).or(odds);
                paired.intoArray(out, 0, SPECIES.indexInRange(0, 16));
                return out;
            }

            // Cold path: compress+expand+pair for :: without IPv4
            if (emptyCount > 0 && !hasDot) {
                long expandBits = computeExpandMask(colonBits, len, nc, segs, pad);
                if (expandBits != -1L) {
                    ByteVector hexNibs = r.compress(keep);
                    ByteVector padded = hexNibs.expand(VectorMask.fromLong(SPECIES, expandBits));
                    ByteVector evens = padded.rearrange(SHUFFLE_EVEN);
                    ByteVector odds  = padded.rearrange(SHUFFLE_ODD);
                    ByteVector paired = evens.mul((byte) 16).or(odds);
                    paired.intoArray(out, 0, SPECIES.indexInRange(0, 16));
                    return out;
                }
            }

            // Extract longs from nibble vector (needed for per-segment loop)
            LongVector lv = (LongVector) r.reinterpretAsLongs();
            int nLongs = (len + 7) / 8;
            long n0 = 0, n1 = 0, n2 = 0, n3 = 0, n4 = 0, n5 = 0;
            if (nLongs > 0) n0 = lv.lane(0);
            if (nLongs > 1) n1 = lv.lane(1);
            if (nLongs > 2) n2 = lv.lane(2);
            if (nLongs > 3) n3 = lv.lane(3);
            if (nLongs > 4) n4 = lv.lane(4);
            if (nLongs > 5) n5 = lv.lane(5);

            // Fill colon positions for per-segment loop (IPv4 suffix)
            if (ccPairs == 0) fillColonPositions(colonBits, col);

            // Fallback: per-segment loop (IPv4 suffix or expand failed)
            int oi = 0;
            boolean ddInserted = false;
            for (int i = 0; i < segs; i++) {
                int start = i == 0 ? 0 : col[i - 1] + 1;
                int end = i == nc ? len : col[i];
                boolean isEmpty = start == end;
                boolean isLast  = i == segs - 1;
                boolean isHex   = !isLast || !hasDot;

                if (isEmpty) {
                    if (!ddInserted) {
                        for (int p = 0; p < pad; p++) { out[oi++] = 0; out[oi++] = 0; }
                        ddInserted = true;
                    }
                } else if (isHex) {
                    int span = end - start;
                    if (span < 1 || span > 4) return null;
                    int li = start / 8;
                    int bo = start % 8;
                    long lo = switch (li) {
                        case 0 -> n0; case 1 -> n1; case 2 -> n2;
                        case 3 -> n3; case 4 -> n4; case 5 -> n5;
                        default -> 0; };
                    long chunk;
                    if (bo == 0) {
                        chunk = lo;
                    } else {
                        long hi = switch (li + 1) {
                            case 0 -> n0; case 1 -> n1; case 2 -> n2;
                            case 3 -> n3; case 4 -> n4; case 5 -> n5;
                            default -> 0; };
                        chunk = (hi << (64 - bo * 8)) | (lo >>> (bo * 8));
                    }
                    int hexVal;
                    switch (span) {
                        case 1 -> hexVal = (int)(chunk) & 0xFF;
                        case 2 -> hexVal = ((int)(chunk) & 0xFF) << 4 | ((int)(chunk >>> 8) & 0xFF);
                        case 3 -> hexVal = ((int)(chunk) & 0xFF) << 8 | ((int)(chunk >>> 8) & 0xFF) << 4 | ((int)(chunk >>> 16) & 0xFF);
                        case 4 -> hexVal = ((int)(chunk) & 0xFF) << 12 | ((int)(chunk >>> 8) & 0xFF) << 8
                                        | ((int)(chunk >>> 16) & 0xFF) << 4 | ((int)(chunk >>> 24) & 0xFF);
                        default -> hexVal = 0;
                    }
                    out[oi++] = (byte)(hexVal >> 8);
                    out[oi++] = (byte)hexVal;
                } else {
                    if (dotCount != 3) return null;
                    ipv4Suffix(input, off + start, end - start, out, oi);
                    oi += 4;
                }
            }
        } else {
            // Multi-vector fallback: convert to HEX_BUF, then assemble
            if (!convertHex(input, off, len, colonBits, dotBits)) return null;
            if (ccPairs == 0) fillColonPositions(colonBits, col);
            int oi = 0;
            boolean ddInserted = false;
            for (int i = 0; i < segs; i++) {
                int start = i == 0 ? 0 : col[i - 1] + 1;
                int end = i == nc ? len : col[i];
                boolean isEmpty = start == end;
                boolean isLast  = i == segs - 1;
                boolean isHex   = !isLast || !hasDot;

                if (isEmpty) {
                    if (!ddInserted) {
                        for (int p = 0; p < pad; p++) { out[oi++] = 0; out[oi++] = 0; }
                        ddInserted = true;
                    }
                } else if (isHex) {
                    int span = end - start;
                    int v = 0;
                    for (int j = 0; j < span; j++) v = (v << 4) | (HEX_BUF[start + j] & 0xFF);
                    out[oi++] = (byte)(v >> 8);
                    out[oi++] = (byte)v;
                } else {
                    if (dotCount != 3) return null;
                    ipv4Suffix(input, off + start, end - start, out, oi);
                    oi += 4;
                }
            }
        }

        return out;
    }

    // -----------------------------------------------------------------
    //  Vector helpers
    // -----------------------------------------------------------------

    static long findDelimiters(byte[] buf, int off, int len, byte delim) {
        long result = 0;
        for (int i = 0; i < len; i += SL) {
            int remain = len - i;
            int vl = Math.min(SL, remain);
            VectorMask<Byte> lm = SPECIES.indexInRange(0, vl);
            ByteVector v = ByteVector.fromArray(SPECIES, buf, off + i, lm);
            long bits = v.compare(VectorOperators.EQ, delim).toLong();
            bits &= (1L << vl) - 1;
            result |= bits << i;
        }
        return result;
    }

    /** Convert every byte to its hex nibble (0-15), validating during the
     *  vector pass. Returns false if any non-delimiter byte is invalid.
     *  Results written to HEX_BUF (caller reads from there).
     *  Shifts hi bytes down by 64 then uses a single LUT rearrange. */
    static boolean convertHex(byte[] buf, int off, int len,
                              long colonBits, long dotBits) {
        long delims = colonBits | dotBits;

        for (int i = 0; i < len; i += SL) {
            int remain = len - i;
            int vl = Math.min(SL, remain);
            VectorMask<Byte> lm = SPECIES.indexInRange(0, vl);

            ByteVector v = ByteVector.fromArray(SPECIES, buf, off + i, lm);
            // split into lo bytes (<64) and hi bytes (≥64), shift hi down
            VectorMask<Byte> isHi = v.compare(VectorOperators.GE, (byte) 64);
            ByteVector idx = v.blend(v.sub((byte) 64), isHi);
            VectorShuffle<Byte> shuf = idx.toShuffle();
            ByteVector r = LUT.rearrange(shuf);

            // validate during conversion: check non-delimiter bytes for -1
            long invalidBits = r.compare(VectorOperators.LT, (byte) 0).toLong();
            long nonDelimInChunk = ((~delims) >>> i) & ((1L << vl) - 1);
            if ((invalidBits & nonDelimInChunk) != 0) return false;

            r.intoArray(HEX_BUF, i, lm);
        }
        return true;
    }

    // -----------------------------------------------------------------
    //  IPv4 suffix (scalar — tiny, not worth vectorizing)
    // -----------------------------------------------------------------

    /** Build expand mask for all inputs (including :: empty segments).
     *  Each segment gets 4 nibble-slot positions with right-aligned padding.
     *  Empty segments (span=0) produce all zeros in their slot.
     *  Returns -1L if any non-empty segment has an invalid span. */
    private static long computeExpandMask(long colonBits, int len, int nc, int segs, int pad) {
        long expandBits = 0;
        int bitPos = 0;
        int start = 0;
        long bits = colonBits;
        boolean emptyExpanded = false;
        for (int i = 0; i < segs; i++) {
            int end = i < nc ? Long.numberOfTrailingZeros(bits) : len;
            int span = end - start;
            if (span == 0) {
                // first empty segment (from ::) expands to pad zero groups
                if (!emptyExpanded) {
                    bitPos += pad * 4;
                    emptyExpanded = true;
                }
                if (i < nc) bits &= bits - 1;
                start = end + 1;
                continue;
            }
            if (span < 1 || span > 4) return -1L;
            int shift = 4 - span;
            expandBits |= ((1L << span) - 1) << (bitPos + shift);
            bitPos += 4;
            if (i < nc) bits &= bits - 1;
            start = end + 1;
        }
        return expandBits;
    }

    private static void fillColonPositions(long bits, int[] col) {
        for (int i = 0; bits != 0; i++) {
            col[i] = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
        }
    }

    static void ipv4Suffix(byte[] b, int off, int len, byte[] out, int oi) {
        int[] dotPos = new int[3];
        int di = 0;
        for (int i = 0; i < len; i++) if (b[off + i] == '.') dotPos[di++] = i;
        int[] segEnds = { dotPos[0], dotPos[1], dotPos[2], len };
        int prev = 0;
        for (int oct = 0; oct < 4; oct++) {
            int v = 0;
            for (int j = prev; j < segEnds[oct]; j++)
                v = v * 10 + (b[off + j] - '0');
            out[oi + oct] = (byte)v;
            prev = segEnds[oct] + 1;
        }
    }
}
