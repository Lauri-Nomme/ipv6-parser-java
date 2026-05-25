package ipv6parse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Ipv6ParserVectorTest {

    @BeforeAll
    static void requiresAvx512() {
        assumeTrue(Ipv6ParserVector.SPECIES.length() >= 48,
            "Vector parser requires AVX-512 (SPECIES >= 48), got " +
            Ipv6ParserVector.SPECIES.length());
    }

    static Stream<Arguments> validAddresses() {
        return Stream.of(
            Arguments.of("2001:db8::1"),
            Arguments.of("::1"),
            Arguments.of("fe80::1"),
            Arguments.of("2001:db8:0:0:0:0:0:1"),
            Arguments.of("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
            Arguments.of("::ffff:192.168.0.1"),
            Arguments.of("2001:db8::c0a8:101"),
            Arguments.of("2001:0db8:0000:0000:0000:0000:0000:0001"),
            Arguments.of("1234:5678:9abc:def0:1234:5678:9abc:def0"),
            Arguments.of("::"),
            Arguments.of("1::"),
            Arguments.of("::abcd"),
            Arguments.of("2001:db8:0:0:0:0:0:2"),
            Arguments.of("ff02::1"),
            Arguments.of("ff02::2"),
            Arguments.of("::192.168.0.1"),
            Arguments.of("2001:db8:85a3::8a2e:370:7334")
        );
    }

    @ParameterizedTest
    @MethodSource("validAddresses")
    void valid(String addr) {
        byte[] input = addr.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = Ipv6Parser.parse(input);
        byte[] actual = Ipv6ParserVector.parse(input);
        assertArrayEquals(expected, actual, addr);
    }

    static Stream<String> invalidAddresses() {
        return Stream.of(
            "2001:db8:::1",
            "2001:db8::1::2",
            "::1:",
            "2001:db8:gggg::1",
            "2001:db8:0:0:0:0:0:0:1",
            "2001:db8:0:0:0:0:0:0:1:2",
            "2001:db8:12345::1",
            "::ffff:192.168.0.256",
            "::ffff:192.168.0",
            "::ffff:192.168.0.0.1",
            "not-an-ip",
            ""
        );
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void invalid(String addr) {
        byte[] input = addr.getBytes(StandardCharsets.US_ASCII);
        assertNull(Ipv6ParserVector.parse(input), addr);
    }
}