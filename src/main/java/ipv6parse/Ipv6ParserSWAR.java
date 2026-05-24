package ipv6parse;

/**
 * SWAR (Sub-Word Parallelism) port based on D. Lemire's compress/expand
 * approach, using Java 21 {@link Long#compress(long, long)} as the
 * SWAR equivalent of AVX-512 VPCOMPRESS.
 *
 * <p>Data flow (matching the C + VectorCE designs):
 * <pre>
 *   load(8 bytes) → SWAR find(':') / find('.')
 *                → per‑byte hex mask → Long.compress → extract to byte[]
 *   ── after all chunks ──
 *   byte[] packed hex → SWAR hex→nibble (8 at a time)
 *                     → nibble pairs → 16‑byte result
 * </pre>
 */
public class Ipv6ParserSWAR {

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        // ---------- Phase 1: load chunks + SWAR delimiter detection ----------
        int numChunks = (len + 7) / 8;

        long colonBits = 0;
        long dotBits   = 0;
        byte[] hexBuf  = new byte[32];  // max hex digits in an IPv6 address
        int totalHex   = 0;

        for (int ci = 0; ci < numChunks; ci++) {
            int byteOff = ci * 8;
            int chunkLen = Math.min(8, len - byteOff);
            long chunk = loadLong(input, off + byteOff, chunkLen);

            // SWAR byte‑level equality for ':' (0x3A) and '.' (0x2E)
            long colons = findByte(chunk, (byte) ':');
            long dots   = findByte(chunk, (byte) '.');

            // valid bit range for this chunk
            long validBits = -1L >>> (64 - chunkLen * 8);
            colons &= validBits;
            dots   &= validBits;

            // fold into global per‑byte masks (bit i ≡ input position i)
            long colonCompact = Long.compress(colons >>> 7, 0x0101010101010101L);
            colonBits |= colonCompact << (ci * 8);

            long dotCompact = Long.compress(dots >>> 7, 0x0101010101010101L);
            dotBits |= dotCompact << (ci * 8);

            // build hex‑digit per‑byte mask for Long.compress
            long delimBytes = ((colons | dots) >>> 7) * 0xFFL;
            long hexMask = ~delimBytes & validBits;

            int nHex = Long.bitCount(hexMask) >>> 3;
            if (nHex == 0) continue;

            long compressed = Long.compress(chunk, hexMask);

            // validate and extract each hex byte
            long cv = compressed;
            for (int k = 0; k < nHex; k++) {
                int b = (int)(cv & 0xFF);
                if (!isHexByte(b)) return null;
                hexBuf[totalHex + k] = (byte) b;
                cv >>>= 8;
            }
            totalHex += nHex;
        }

        // decode colon positions
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

        // ---------- Phase 2: segment boundaries (shared logic) ----------
        int segs = nc + 1;
        int[] segStart = new int[segs];
        int[] segEnd   = new int[segs];
        segStart[0] = 0;
        for (int i = 0; i < nc; i++) {
            segEnd[i] = col[i];
            segStart[i + 1] = col[i] + 1;
        }
        segEnd[nc] = len;

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

        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad       = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        // validate hex segment sizes
        for (int i = 0; i < segs; i++) {
            boolean isLast = i == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
            }
        }

        // build group sizes
        int[] grpSizes = new int[hexGroups];
        int gi = 0;
        boolean padDone = false;
        for (int si = 0; si < segs; si++) {
            boolean isLast = si == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            boolean empty  = segStart[si] == segEnd[si];
            if (empty && isHex && !padDone) {
                for (int p = 0; p < pad; p++) grpSizes[gi++] = 0;
                padDone = true;
            } else if (!empty && isHex) {
                grpSizes[gi++] = segEnd[si] - segStart[si];
            }
        }

        // ---------- Phase 3: SWAR hex‑to‑nibble conversion (8 bytes at a time) --
        byte[] nibBuf = new byte[totalHex];
        int ni = 0;
        while (ni < totalHex) {
            int chunk = Math.min(8, totalHex - ni);
            // zero out bytes beyond chunk to avoid converting garbage pad
            long v = loadLongFromBytes(hexBuf, ni, chunk);
            long n = swarHexConvert(v);
            // extract and validate only the chunk bytes we care about
            for (int k = 0; k < chunk; k++) {
                int val = (int)(n & 0xFF);
                if ((val & 0xF0) != 0) return null;
                nibBuf[ni + k] = (byte) val;
                n >>>= 8;
            }
            ni += chunk;
        }

        // ---------- Phase 4: assemble output nibble‑by‑nibble ----------
        byte[] out = new byte[16];
        int oi = 0;
        int nibblePos = 0;

        for (int g = 0; g < hexGroups; g++) {
            int size = grpSizes[g];
            if (size == 0) {
                out[oi++] = 0; out[oi++] = 0;
                continue;
            }
            int v = 0;
            for (int k = 0; k < size; k++) {
                v = (v << 4) | (nibBuf[nibblePos++] & 0xFF);
            }
            out[oi++] = (byte)(v >> 8);
            out[oi++] = (byte)(v);
        }

        // ---------- Phase 5: IPv4 suffix (always scalar) ----------
        if (hasDot) {
            if (dotCount != 3) return null;
            int ipv4Off = col[nc - 1] + 1;
            Ipv6Parser.ipv4Suffix(input, off + ipv4Off, len - ipv4Off, out, 12);
            oi += 4;
        }

        return oi == 16 ? out : null;
    }

    // ─────────────────────────────────────────────────────────────────
    //  SWAR primitives
    // ─────────────────────────────────────────────────────────────────

    static long loadLong(byte[] buf, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v |= (buf[off + i] & 0xFFL) << (i * 8);
        }
        return v;
    }

    /** Load up to 8 bytes from a byte[] (not from input). */
    static long loadLongFromBytes(byte[] buf, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v |= (buf[off + i] & 0xFFL) << (i * 8);
        }
        return v;
    }

    /** SWAR byte‑wise equality. Returns MSB set for matching bytes. */
    static long findByte(long chunk, byte b) {
        long pat = b & 0xFFL;
        pat |= pat << 8;  pat |= pat << 16;  pat |= pat << 32;
        long xor = chunk ^ pat;
        return ((xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L);
    }

    /**
     * SWAR hex‑digit → nibble conversion.
     * Each byte must be '0'-'9', 'A'-'F', or 'a'-'f'.
     * Returns bytes each 0x00–0x0F (upper nibble zero).
     */
    static long swarHexConvert(long v) {
        long t = v - 0x3030303030303030L;

        // detect letters via bit 6 ('A'-'F' / 'a'-'f' have 0x40, '0'-'9' don't)
        long isLetter = (v & 0x4040404040404040L) << 1;  // → 0x80 per letter byte

        // subtract 7 from letter bytes (0x80>>>5 | >>>6 | >>>7 = 0x07)
        long adj = (isLetter >>> 5) | (isLetter >>> 6) | (isLetter >>> 7);
        long isLower = t & 0x2020202020202020L;

        return t - (adj | isLower);
    }

    static boolean isHexByte(int b) {
        return (b >= '0' && b <= '9') ||
               (b >= 'A' && b <= 'F') ||
               (b >= 'a' && b <= 'f');
    }
}
