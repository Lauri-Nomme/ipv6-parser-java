import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IPv6 address parser from a byte[] (ASCII), inspired by the algorithmic
 * approach from Daniel Lemire's AVX-512 SIMD parser.
 *
 * <p>The core technique is identical to the SIMD version:
 * <ol>
 *   <li>Locate all ':' and '.' delimiters by scanning
 *   <li>Compute group sizes from delimiter-position differences
 *   <li>Detect "::" by finding consecutive colons
 *   <li>Validate each hex group is 1-4 hex digits
 *   <li>Validate IPv4 suffix (3 dots, 1-3 decimal digits each, no leading zero, ≤255)
 *   <li>Expand "::" into the correct number of zero groups
 *   <li>Convert hex groups to 16-bit big-endian values
 *   <li>Assemble the 16-byte result
 * </ol>
 *
 * <p>Only the parallelism (compress/expand/lookup intrinsics) is lost;
 * the algorithm and validation logic are a direct 1:1 port.
 */
public class Ipv6Parser {

    /**
     * Parse an ASCII-encoded IPv6 address from a byte array.
     *
     * @param input the full byte array
     * @return 16-byte array on success, null on invalid input
     */
    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    /**
     * Parse an ASCII-encoded IPv6 address from a slice of a byte array.
     *
     * @param input the byte array
     * @param off   offset within the array
     * @param len   number of bytes to parse
     * @return 16-byte array on success, null on invalid input
     */
    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        // --- Phase 1: locate ':' positions (cf. _mm512_cmpeq_epu8_mask) ---
        int[] col = new int[8];
        int nc = 0;
        for (int i = off; i < off + len; i++) {
            if (input[i] == ':') {
                col[nc++] = i - off;
            }
        }
        if (nc == 0) return null; // IPv6 must have at least one colon

        // --- Phase 2: detect "::" consecutive colons (cf. ((bitv>>1) & bitv)) ---
        int ccPairs = 0;
        for (int i = 1; i < nc; i++) {
            if (col[i] == col[i - 1] + 1) ccPairs++;
        }
        if (ccPairs > 1) return null;

        // --- Phase 3: detect IPv4 suffix ---
        boolean hasDot = false;
        int dotCount = 0;
        for (int i = off; i < off + len; i++) {
            if (input[i] == '.') { hasDot = true; dotCount++; }
        }

        // --- Phase 4: build segment boundaries ---
        // segs = nc + 1 colon-separated segments
        int segs = nc + 1;
        int[] segStart = new int[segs];
        int[] segEnd   = new int[segs];

        segStart[0] = 0;
        for (int i = 0; i < nc; i++) {
            segEnd[i] = col[i];
            if (i + 1 < segs) segStart[i + 1] = col[i] + 1;
        }
        segEnd[nc] = len;

        // --- Phase 5: identify empty segments (the "::" gap) ---
        int emptyCount = 0;
        int firstEmpty = -1;
        boolean emptiesConsecutive = true;
        int lastEmptyIdx = -2;

        for (int i = 0; i < segs; i++) {
            boolean e = segStart[i] == segEnd[i];
            if (e) {
                emptyCount++;
                if (firstEmpty < 0) firstEmpty = i;
                if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1) {
                    emptiesConsecutive = false;
                }
                lastEmptyIdx = i;
            }
        }

        if (emptyCount > 0 && !emptiesConsecutive) return null;

        // Validate "::" has exactly 1-3 empty segments
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        // --- Phase 6: validate group counts ---
        int hexGroups = hasDot ? 6 : 8;   // number of 16-bit hex groups
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad = hexGroups - hexSegs;

        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        // --- Phase 7: validate non-empty hex segments (1-4 chars, all hex) ---
        for (int i = 0; i < segs; i++) {
            boolean isLast = (i == segs - 1);
            boolean isHex  = !isLast || !hasDot;

            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
                for (int j = segStart[i]; j < segEnd[i]; j++) {
                    if (hexVal(input[off + j]) < 0) return null;
                }
            }
        }

        // --- Phase 8: process segments and assemble output ---
        byte[] out = new byte[16];
        int oi = 0;
        boolean ddInserted = false;

        for (int i = 0; i < segs; i++) {
            boolean isEmpty = segStart[i] == segEnd[i];
            boolean isLast  = (i == segs - 1);
            boolean isHex   = !isLast || !hasDot;

            if (isEmpty) {
                if (!ddInserted) {
                    for (int p = 0; p < pad; p++) {
                        out[oi++] = 0; out[oi++] = 0;
                    }
                    ddInserted = true;
                }
            } else if (isHex) {
                if (!hexGroup(input, off + segStart[i], segEnd[i] - segStart[i], out, oi))
                    return null;
                oi += 2;
            } else {
                // IPv4 suffix (last segment when hasDot)
                if (dotCount != 3) return null;
                if (!ipv4Suffix(input, off + segStart[i], segEnd[i] - segStart[i], out, oi))
                    return null;
                oi += 4;
            }
        }

        return oi == 16 ? out : null;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Convert 1-4 hex chars to a big-endian 16-bit value. */
    private static boolean hexGroup(byte[] b, int off, int len,
                                    byte[] out, int oi) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            int d = hexVal(b[off + i]);
            if (d < 0) return false;
            v = (v << 4) | d;
        }
        out[oi]     = (byte)(v >> 8);
        out[oi + 1] = (byte)(v);
        return true;
    }

    /** Parse dotted-decimal IPv4 suffix (3 dots, 4 octets). */
    private static boolean ipv4Suffix(byte[] b, int off, int len,
                                      byte[] out, int oi) {
        // find dot positions within the suffix
        int[] dotPos = new int[3];
        int di = 0;
        for (int i = 0; i < len; i++) {
            if (b[off + i] == '.') {
                if (di >= 3) return false;
                dotPos[di++] = i;
            }
        }
        if (di != 3) return false;

        int[] segEnds = { dotPos[0], dotPos[1], dotPos[2], len };
        int prev = 0;
        for (int oct = 0; oct < 4; oct++) {
            int span = segEnds[oct] - prev;
            if (span < 1 || span > 3) return false;
            // no leading zero (zero itself is fine, but "00" or "01" is not)
            if (span > 1 && b[off + prev] == '0') return false;
            int v = 0;
            for (int j = prev; j < segEnds[oct]; j++) {
                byte c = b[off + j];
                if (c < '0' || c > '9') return false;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return false;
            out[oi + oct] = (byte)v;
            prev = segEnds[oct] + 1;
        }
        return true;
    }

    private static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }

    // ---------------------------------------------------------------
    // Convenience wrappers
    // ---------------------------------------------------------------

    /**
     * Parse and return an {@link InetAddress}.
     * @throws UnknownHostException if parsing fails
     */
    public static InetAddress toInetAddress(byte[] input)
            throws UnknownHostException {
        byte[] addr = parse(input);
        if (addr == null)
            throw new UnknownHostException("Invalid IPv6 address");
        return InetAddress.getByAddress(addr);
    }

    public static InetAddress toInetAddress(byte[] input, int off, int len)
            throws UnknownHostException {
        byte[] addr = parse(input, off, len);
        if (addr == null)
            throw new UnknownHostException("Invalid IPv6 address");
        return InetAddress.getByAddress(addr);
    }
}
