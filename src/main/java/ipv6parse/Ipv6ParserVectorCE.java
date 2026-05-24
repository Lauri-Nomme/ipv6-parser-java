package ipv6parse;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector API port using compress(VectorMask) + expand(VectorMask)
 * added in JDK 21 (JEP 448), matching the C code's dataflow closely:
 *
 * <pre>
 *   load → compare(delim) → bitmask
 *        → compress(hex_mask) → convert to nibbles
 *        → expand(pad_mask) → combine pairs → 16 bytes
 * </pre>
 *
 * When the input exceeds one vector lane the non-compress vector fallback
 * ({@link Ipv6ParserVector}) is used for the hex conversion.
 */
public class Ipv6ParserVectorCE {

    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    static final int SL = SPECIES.length();

    // reusable scalar convert-hex (single-pass, no intermediate array)
    private static final Ipv6Parser FALLBACK = new Ipv6Parser();  // not used directly

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        int sl = SL;

        // ---------- Phase 1: vectorized delimiter detection ----------
        long colonBits = findDelims(input, off, len, (byte) ':');
        long dotBits   = findDelims(input, off, len, (byte) '.');

        int[] col = new int[8];
        int nc = 0;
        long bits = colonBits;
        while (bits != 0 && nc < 8) {
            col[nc++] = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
        }
        if (nc == 0) return null;

        int ccPairs = 0;
        for (int i = 1; i < nc; i++)
            if (col[i] == col[i - 1] + 1) ccPairs++;
        if (ccPairs > 1) return null;

        boolean hasDot = dotBits != 0;
        int dotCount = Long.bitCount(dotBits);

        // ---------- Phase 2: segment boundaries ----------
        int segs = nc + 1;
        int[] segStart = new int[segs];
        int[] segEnd   = new int[segs];
        segStart[0] = 0;
        for (int i = 0; i < nc; i++) {
            segEnd[i] = col[i];
            segStart[i + 1] = col[i] + 1;
        }
        segEnd[nc] = len;

        // empty segments (::)
        int emptyCount = 0;
        int firstEmpty = -1;
        boolean consec = true;
        int lastEmptyIdx = -2;
        for (int i = 0; i < segs; i++) {
            boolean e = segStart[i] == segEnd[i];
            if (e) {
                emptyCount++;
                if (firstEmpty < 0) firstEmpty = i;
                if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1) consec = false;
                lastEmptyIdx = i;
            }
        }
        if (emptyCount > 0 && !consec) return null;
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad       = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        // validate hex segment sizes
        int hexCharCount = 0;
        for (int i = 0; i < segs; i++) {
            boolean isLast = i == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
                hexCharCount += span;
            }
        }

        // ---------- Phase 3: build output group-sizes array ----------
        int[] grpSizes = new int[hexGroups];
        int gi = 0;
        boolean padDone = false;
        for (int si = 0; si < segs; si++) {
            boolean isLast = si == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            boolean empty  = segStart[si] == segEnd[si];

            if (empty && isHex && !padDone) {
                // insert :: padding here (first empty segment triggers it)
                for (int p = 0; p < pad; p++) grpSizes[gi++] = 0;
                padDone = true;
            } else if (!empty && isHex) {
                grpSizes[gi++] = segEnd[si] - segStart[si];
            } // else IPv4 suffix — handled later
        }

        // ---------- Phase 4: compress + hex-convert (or fallback) ----
        byte[] out = new byte[16];
        int oi = 0;

        if (len <= sl) {
            // ──── fast path: whole input fits in one vector ─────────
            if (!compressExpandPath(input, off, len, colonBits, dotBits,
                                    grpSizes, hasDot, out))
                return null;
            oi = hasDot ? 12 : 16; // hex part done
        } else {
            // ──── fallback: use the non-compress vector approach ─────
            byte[] hexVals = Ipv6ParserVector.convertHex(input, off, len);
            // validate
            long nonDelim = ((1L << len) - 1) & ~colonBits & ~dotBits;
            long tmp = nonDelim;
            while (tmp != 0) {
                int p = Long.numberOfTrailingZeros(tmp);
                if (hexVals[p] < 0) return null;
                tmp &= tmp - 1;
            }
            // iterate over hexVals skipping colons (hexVals is indexed by
            // input position, not by compressed group order)
            int ip = 0;
            for (int g = 0; g < hexGroups; g++) {
                if (grpSizes[g] == 0) { out[oi++] = 0; out[oi++] = 0; continue; }
                int v = 0;
                for (int k = 0; k < grpSizes[g]; k++) {
                    while (ip < len && (colonBits & (1L << ip)) != 0) ip++;
                    v = (v << 4) | hexVals[ip++];
                }
                out[oi++] = (byte)(v >> 8); out[oi++] = (byte)(v);
            }
        }

        // ---------- Phase 5: IPv4 suffix (always scalar) -------------
        if (hasDot) {
            if (dotCount != 3) return null;
            int ipv4Off = col[nc - 1] + 1;
            Ipv6Parser.ipv4Suffix(input, off + ipv4Off, len - ipv4Off, out, 12);
            oi += 4;
        }

        return oi == 16 ? out : null;
    }

    // ────────────────────────────────────────────────────────────────
    //  Single-vector compress → convert → expand → combinate
    // ────────────────────────────────────────────────────────────────

    private static boolean compressExpandPath(byte[] input, int off, int len,
                                              long colonBits, long dotBits,
                                              int[] grpSizes, boolean hasDot,
                                              byte[] out) {
        int sl = SL;
        byte[] padded = new byte[sl];
        System.arraycopy(input, off, padded, 0, len);

        VectorMask<Byte> loadMask = SPECIES.indexInRange(0, len);
        ByteVector vec = ByteVector.fromArray(SPECIES, padded, 0, loadMask);

        // build compress mask: keep non-':' non-'.' bytes within len
        long delims = colonBits | dotBits;
        long keepBits = ((1L << len) - 1) & ~delims;
        VectorMask<Byte> keep = VectorMask.fromLong(SPECIES, keepBits);

        // compress hex characters into contiguous lanes 0 ..
        ByteVector hexVec = vec.compress(keep);

        // convert to nibble values
        ByteVector nibs = hexConvert(hexVec);

        // validate: no -1 in the compressed hex lanes
        int hexChars = 0;
        for (int gs : grpSizes) hexChars += gs;
        long validLanes = (hexChars >= 64) ? -1L : (1L << hexChars) - 1;
        VectorMask<Byte> invalid =
            nibs.compare(VectorOperators.LT, (byte) 0);
        long bad = invalid.toLong() & validLanes;
        if (bad != 0) return false;

        // build expand mask from group sizes
        long expandBits = 0;
        int bitPos = 0;
        for (int gs : grpSizes) {
            if (gs == 0) {
                bitPos += 4;
            } else {
                // right‑align within 4 nibble‑slots
                int shift = 4 - gs;
                for (int k = 0; k < gs; k++)
                    expandBits |= 1L << (bitPos + shift + k);
                bitPos += 4;
            }
        }
        VectorMask<Byte> expandMask =
            VectorMask.fromLong(SPECIES, expandBits);

        // expand → each group has 4 nibbles, leading zeros filled
        ByteVector paddedNibs = nibs.expand(expandMask);

        // scalar combine: 2 nibbles → 1 byte
        byte[] tmp = new byte[sl];
        paddedNibs.intoArray(tmp, 0);

        int hexGroups = hasDot ? 6 : 8;
        int oi = 0;
        for (int g = 0; g < hexGroups; g++) {
            int b0 = tmp[g * 4] & 0xff;
            int b1 = tmp[g * 4 + 1] & 0xff;
            int b2 = tmp[g * 4 + 2] & 0xff;
            int b3 = tmp[g * 4 + 3] & 0xff;
            out[oi++] = (byte)((b0 << 4) | b1);
            out[oi++] = (byte)((b2 << 4) | b3);
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    //  Hex conversion (shared)
    // ────────────────────────────────────────────────────────────────

    static ByteVector hexConvert(ByteVector v) {
        var Z0 = v.broadcast((byte)'0');
        var A  = v.broadcast((byte)'A');
        var F  = v.broadcast((byte)'F');
        var a  = v.broadcast((byte)'a');
        var f  = v.broadcast((byte)'f');
        var N1 = v.broadcast((byte)-1);

        var v0 = v.sub(Z0);

        var digit = v0.compare(VectorOperators.GE, (byte) 0)
                      .and(v0.compare(VectorOperators.LE, (byte) 9));
        var upper = v.compare(VectorOperators.GE, A)
                      .and(v.compare(VectorOperators.LE, F));
        var lower = v.compare(VectorOperators.GE, a)
                      .and(v.compare(VectorOperators.LE, f));

        var r = N1;
        r = r.blend(v0, digit);
        r = r.blend(v0.sub((byte) 7),  upper);
        r = r.blend(v0.sub((byte) 39), lower);
        return r;
    }

    // ────────────────────────────────────────────────────────────────
    //  Delimiter finder (shared with Ipv6ParserVector)
    // ────────────────────────────────────────────────────────────────

    static long findDelims(byte[] buf, int off, int len, byte delim) {
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
}
