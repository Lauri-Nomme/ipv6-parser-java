package ipv6parse;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class Ipv6ParserVector {

    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    static final int SL = SPECIES.length();
    private static final ByteVector Z0 = ByteVector.broadcast(SPECIES, (byte) '0');
    private static final ByteVector A  = ByteVector.broadcast(SPECIES, (byte) 'A');
    private static final ByteVector F  = ByteVector.broadcast(SPECIES, (byte) 'F');
    private static final ByteVector a  = ByteVector.broadcast(SPECIES, (byte) 'a');
    private static final ByteVector f  = ByteVector.broadcast(SPECIES, (byte) 'f');
    private static final ByteVector N1 = ByteVector.broadcast(SPECIES, (byte) -1);

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        // ---- Phase 1: vectorized delimiter detection -----------------
        long colonBits = findDelimiters(input, off, len, (byte) ':');
        long dotBits   = findDelimiters(input, off, len, (byte) '.');

        int[] col = new int[8];
        int nc = 0;
        long bits = colonBits;
        while (bits != 0 && nc < 8) {
            col[nc++] = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
        }
        if (nc == 0) return null;

        int ccPairs = 0;
        for (int i = 1; i < nc; i++) {
            if (col[i] == col[i - 1] + 1) ccPairs++;
        }
        if (ccPairs > 1) return null;

        boolean hasDot = dotBits != 0;
        int dotCount = Long.bitCount(dotBits);

        // ---- Phase 2: build segment boundaries -----------------------
        int segs = nc + 1;
        int[] segStart = new int[segs];
        int[] segEnd   = new int[segs];
        segStart[0] = 0;
        for (int i = 0; i < nc; i++) {
            segEnd[i] = col[i];
            segStart[i + 1] = col[i] + 1;
        }
        segEnd[nc] = len;

        // ---- Phase 3: detect empty segments (::) ---------------------
        int emptyCount = 0;
        int firstEmpty = -1;
        boolean emptiesConsecutive = true;
        int lastEmptyIdx = -2;
        for (int i = 0; i < segs; i++) {
            boolean e = segStart[i] == segEnd[i];
            if (e) {
                emptyCount++;
                if (firstEmpty < 0) firstEmpty = i;
                if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1)
                    emptiesConsecutive = false;
                lastEmptyIdx = i;
            }
        }
        if (emptyCount > 0 && !emptiesConsecutive) return null;
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        // ---- Phase 4: validate group counts --------------------------
        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        // ---- Phase 5: validate & convert every char via vectors ------
        byte[] hexVals = convertHex(input, off, len, colonBits, dotBits);
        if (hexVals == null) return null;

        // validate hex segment sizes
        for (int i = 0; i < segs; i++) {
            boolean isLast = i == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
            }
        }

        // ---- Phase 6: assemble output --------------------------------
        byte[] out = new byte[16];
        int oi = 0;
        boolean ddInserted = false;

        for (int i = 0; i < segs; i++) {
            boolean isEmpty = segStart[i] == segEnd[i];
            boolean isLast  = i == segs - 1;
            boolean isHex   = !isLast || !hasDot;

            if (isEmpty) {
                if (!ddInserted) {
                    for (int p = 0; p < pad; p++) {
                        out[oi++] = 0; out[oi++] = 0;
                    }
                    ddInserted = true;
                }
            } else if (isHex) {
                hexGroupFromVals(hexVals, segStart[i], segEnd[i] - segStart[i], out, oi);
                oi += 2;
            } else {
                if (dotCount != 3) return null;
                ipv4Suffix(input, off + segStart[i], segEnd[i] - segStart[i], out, oi);
                oi += 4;
            }
        }

        return oi == 16 ? out : null;
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
     *  vector pass. Returns null if any non-delimiter byte is invalid. */
    static byte[] convertHex(byte[] buf, int off, int len,
                             long colonBits, long dotBits) {
        byte[] out = new byte[len];
        long delims = colonBits | dotBits;

        for (int i = 0; i < len; i += SL) {
            int remain = len - i;
            int vl = Math.min(SL, remain);
            VectorMask<Byte> lm = SPECIES.indexInRange(0, vl);

            ByteVector v  = ByteVector.fromArray(SPECIES, buf, off + i, lm);
            ByteVector v0 = v.sub(Z0);

            VectorMask<Byte> digit = v0.compare(VectorOperators.GE, (byte) 0)
                .and(v0.compare(VectorOperators.LE, (byte) 9));
            VectorMask<Byte> upper = v.compare(VectorOperators.GE, A)
                .and(v.compare(VectorOperators.LE, F));
            VectorMask<Byte> lower = v.compare(VectorOperators.GE, a)
                .and(v.compare(VectorOperators.LE, f));

            ByteVector r = N1;
            r = r.blend(v0,       digit);
            r = r.blend(v0.sub((byte) 7),  upper);
            r = r.blend(v0.sub((byte) 39), lower);

            // validate during conversion: check non-delimiter bytes for -1
            long invalidBits = r.compare(VectorOperators.LT, (byte) 0).toLong();
            long nonDelimInChunk = ((~delims) >>> i) & ((1L << vl) - 1);
            if ((invalidBits & nonDelimInChunk) != 0) return null;

            r.intoArray(out, i, lm);
        }
        return out;
    }

    static void hexGroupFromVals(byte[] vals, int start, int len,
                                  byte[] out, int oi) {
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 4) | vals[start + i];
        out[oi]     = (byte)(v >> 8);
        out[oi + 1] = (byte)(v);
    }

    // -----------------------------------------------------------------
    //  IPv4 suffix (scalar — tiny, not worth vectorizing)
    // -----------------------------------------------------------------

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
