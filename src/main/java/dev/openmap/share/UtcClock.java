package dev.openmap.share;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class UtcClock {

    private static final int PACKET_BYTES = 48;
    private static final int PORT = 123;
    private static final int TIMEOUT_MILLIS = 2_000;
    private static final long BUDGET_MILLIS = 12_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long AGREEMENT_MILLIS = 2_000L;
    private static final long STEP_MILLIS = 2_000L;
    private static final long REFRESH_MILLIS = 30L * 60L * 1_000L;
    private static final long RETRY_MILLIS = 5L * 60L * 1_000L;
    private static final int QUORUM = 3;
    private static final long EPOCH_1900_TO_1970 = 2_208_988_800L;
    private static final int ORIGINATE_AT = 24;
    private static final int RECEIVE_AT = 32;
    private static final int TRANSMIT_AT = 40;
    private static final byte CLIENT_HEAD = (byte) 0x23;
    private static final String[] POOL = {
            "pool.ntp.org",
            "0.pool.ntp.org",
            "1.pool.ntp.org",
            "2.pool.ntp.org",
            "3.pool.ntp.org"};
    private static final SecureRandom NONCE = new SecureRandom();
    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int ANY_OCTET = -1;
    private static final int FIRST_RESERVED_OCTET = 240;
    // Row: first byte, second byte range, third byte or ANY_OCTET.
    private static final int[][] RESERVED_V4 = {
            {0, 0, 255, ANY_OCTET},
            {127, 0, 255, ANY_OCTET},
            {100, 64, 127, ANY_OCTET},
            {192, 0, 0, 0},
            {192, 0, 0, 2},
            {198, 18, 19, ANY_OCTET},
            {198, 51, 51, 100},
            {203, 0, 0, 113}};
    private static final int ULA_MASK = 0xFE;
    private static final int ULA_PREFIX = 0xFC;
    private static final int NAT64_FIRST = 0x00;
    private static final int NAT64_SECOND = 0x64;
    private static final int NAT64_THIRD = 0xFF;
    private static final int NAT64_FOURTH = 0x9B;
    private static final int V4_COMPATIBLE_ZEROS = 12;

    public enum Source {
        NTP,
        HTTP_DATE,
        PC
    }

    @FunctionalInterface
    interface NameResolver {

        InetAddress[] resolve(String name) throws IOException;
    }

    @FunctionalInterface
    interface Exchange {

        byte[] exchange(InetAddress address, byte[] request) throws IOException;
    }

    @FunctionalInterface
    interface PcClock {

        long millis();

        default long monotonicNanos() {
            return System.nanoTime();
        }
    }

    static record Sample(long offsetMillis, long delayMillis) {
    }

    private record Moment(long pcMillis, long nanos) {
    }

    private record Reading(boolean grounded, long offsetMillis, Source source, Moment measuredAt,
                           Moment lastAttempt, boolean attempted, boolean failedAttempt) {

        private Reading failedAt(Moment attempt) {
            Moment measured = grounded ? measuredAt : attempt;
            return new Reading(grounded, offsetMillis, source, measured, attempt, true, true);
        }

        private Reading notedAt(long offset, Moment at) {
            return new Reading(true, offset, Source.HTTP_DATE, at, lastAttempt, attempted,
                    failedAttempt);
        }
    }

    private static final class Collector {

        private static final UtcClock CLOCK = new UtcClock();

        private Collector() {
        }
    }

    private final NameResolver resolver;
    private final Exchange exchange;
    private final PcClock pcClock;
    private final AtomicReference<Reading> reading;

    public UtcClock() {
        this(UtcClock::resolve, UtcClock::exchange, System::currentTimeMillis);
    }

    UtcClock(NameResolver resolver, Exchange exchange, PcClock pcClock) {
        this.resolver = resolver;
        this.exchange = exchange;
        this.pcClock = pcClock;
        Moment never = new Moment(0L, 0L);
        reading = new AtomicReference<>(new Reading(false, 0L, Source.PC, never, never, false, false));
    }

    public static UtcClock collector() {
        return Collector.CLOCK;
    }

    public long nowMillis() {
        Reading current = reading.get();
        return pcClock.millis() + current.offsetMillis();
    }

    public boolean grounded() {
        return reading.get().grounded();
    }

    // Blocking; worker thread only.
    public void measure() {
        long attemptedAt = pcClock.millis();
        long attemptedNanos = pcClock.monotonicNanos();
        Sample[] samples = new Sample[POOL.length];
        int found = 0;
        for (String server : POOL) {
            if (pcClock.monotonicNanos() - attemptedNanos >= BUDGET_MILLIS * NANOS_PER_MILLI) {
                break;
            }
            Sample sample = ask(server);
            if (sample != null) {
                samples[found] = sample;
                found++;
            }
        }
        Sample consensus = consensus(samples, found);
        Moment attempt = new Moment(attemptedAt, attemptedNanos);
        if (consensus == null) {
            recordFailedAttempt(attempt);
        } else {
            reading.set(new Reading(true, consensus.offsetMillis(), Source.NTP, attempt, attempt, true, false));
        }
    }

    public boolean due(long pcNowMillis) {
        Reading current = reading.get();
        if (!current.attempted()) {
            return true;
        }
        Moment since = current.lastAttempt();
        long interval = current.failedAttempt() ? RETRY_MILLIS : REFRESH_MILLIS;
        return pcNowMillis - since.pcMillis() >= interval || stepped(since, pcNowMillis);
    }

    public void noteHttpDate(String dateHeader, long requestStartedPcMillis,
                             long replyReceivedPcMillis) {
        long headerMillis = parseHttpDate(dateHeader);
        if (headerMillis == Long.MIN_VALUE) {
            return;
        }
        long middle = requestStartedPcMillis
                + (replyReceivedPcMillis - requestStartedPcMillis) / 2L;
        long offset = headerMillis + 500L - middle;
        boolean settled = false;
        while (!settled) {
            Reading current = reading.get();
            if (current.source() == Source.NTP
                    && !stepped(current.measuredAt(), replyReceivedPcMillis)) {
                settled = true;
            } else {
                Moment at = new Moment(replyReceivedPcMillis, pcClock.monotonicNanos());
                settled = reading.compareAndSet(current, current.notedAt(offset, at));
            }
        }
    }

    private boolean stepped(Moment since, long pcNowMillis) {
        long pcPassed = pcNowMillis - since.pcMillis();
        long monotonicPassed = (pcClock.monotonicNanos() - since.nanos()) / NANOS_PER_MILLI;
        return Math.abs(pcPassed - monotonicPassed) > STEP_MILLIS;
    }

    private void recordFailedAttempt(Moment attempt) {
        boolean settled = false;
        while (!settled) {
            Reading current = reading.get();
            settled = reading.compareAndSet(current, current.failedAt(attempt));
        }
    }

    private Sample ask(String server) {
        Sample answer;
        try {
            InetAddress address = firstRoutable(resolver.resolve(server));
            if (address == null) {
                answer = null;
            } else {
                long originated = pcClock.millis();
                byte[] request = request(originated);
                byte[] reply = exchange.exchange(address, request);
                long received = pcClock.millis();
                answer = sample(request, reply, originated, received);
            }
        } catch (IOException | RuntimeException failure) {
            answer = null;
        }
        return answer;
    }

    // Null without a routable address in answer.
    private static InetAddress firstRoutable(InetAddress[] answer) {
        InetAddress found = null;
        if (answer != null) {
            for (int at = 0; at < answer.length && found == null; at++) {
                InetAddress address = answer[at];
                if (address != null && !reserved(address)) {
                    found = address;
                }
            }
        }
        return found;
    }

    // Mirrors the node's rule for refused addresses.
    static boolean reserved(InetAddress address) {
        boolean refused = address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress();
        if (!refused) {
            byte[] bytes = address.getAddress();
            if (bytes.length == IPV4_BYTES) {
                refused = reservedV4(bytes);
            } else if (bytes.length == IPV6_BYTES) {
                refused = reservedV6(bytes);
            } else {
                refused = true;
            }
        }
        return refused;
    }

    private static boolean reservedV4(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;
        boolean refused = first >= FIRST_RESERVED_OCTET;
        for (int at = 0; at < RESERVED_V4.length && !refused; at++) {
            int[] block = RESERVED_V4[at];
            refused = first == block[0] && second >= block[1] && second <= block[2]
                    && (block[3] == ANY_OCTET || third == block[3]);
        }
        return refused;
    }

    private static boolean reservedV6(byte[] bytes) {
        boolean refused = ((bytes[0] & ULA_MASK) == ULA_PREFIX)
                || ((bytes[0] & 0xFF) == NAT64_FIRST && (bytes[1] & 0xFF) == NAT64_SECOND
                        && (bytes[2] & 0xFF) == NAT64_THIRD && (bytes[3] & 0xFF) == NAT64_FOURTH);
        if (!refused) {
            boolean zeros = true;
            for (int at = 0; at < V4_COMPATIBLE_ZEROS && zeros; at++) {
                zeros = bytes[at] == 0;
            }
            refused = zeros;
        }
        return refused;
    }

    private static byte[] request(long originated) {
        byte[] request = new byte[PACKET_BYTES];
        request[0] = CLIENT_HEAD;
        writeStamp(request, TRANSMIT_AT, originated);
        writeUnsigned32(request, TRANSMIT_AT + 4, NONCE.nextInt() & 0xFFFF_FFFFL);
        return request;
    }

    static Sample sample(byte[] request, byte[] reply, long originated, long received)
            throws IOException {
        if (reply == null || reply.length < PACKET_BYTES) {
            throw new IOException();
        }
        int head = reply[0] & 0xFF;
        if ((head & 0x07) != 4 || (head >>> 6) == 3) {
            throw new IOException();
        }
        int stratum = reply[1] & 0xFF;
        if (stratum < 1 || stratum > 15) {
            throw new IOException();
        }
        if (!Arrays.equals(Arrays.copyOfRange(request, TRANSMIT_AT, TRANSMIT_AT + 8),
                Arrays.copyOfRange(reply, ORIGINATE_AT, ORIGINATE_AT + 8))) {
            throw new IOException();
        }
        if (zero(reply, RECEIVE_AT) || zero(reply, TRANSMIT_AT)) {
            throw new IOException();
        }
        long serverReceived = readStamp(reply, RECEIVE_AT);
        long serverTransmitted = readStamp(reply, TRANSMIT_AT);
        long offset = ((serverReceived - originated) + (serverTransmitted - received)) / 2L;
        long delay = (received - originated) - (serverTransmitted - serverReceived);
        if (delay < 0L) {
            throw new IOException();
        }
        return new Sample(offset, delay);
    }

    private static Sample consensus(Sample[] samples, int found) {
        if (found < QUORUM) {
            return null;
        }
        sort(samples, found);
        int bestFirst = 0;
        int bestCount = 0;
        for (int first = 0; first <= found - QUORUM; first++) {
            int last = first;
            while (last + 1 < found && samples[last + 1].offsetMillis()
                    - samples[first].offsetMillis() <= AGREEMENT_MILLIS) {
                last++;
            }
            int count = last - first + 1;
            if (count > bestCount) {
                bestFirst = first;
                bestCount = count;
            }
        }
        if (bestCount < QUORUM) {
            return null;
        }
        return samples[bestFirst + bestCount / 2];
    }

    private static void sort(Sample[] samples, int found) {
        for (int index = 1; index < found; index++) {
            Sample sample = samples[index];
            int insertion = index;
            while (insertion > 0 && samples[insertion - 1].offsetMillis()
                    > sample.offsetMillis()) {
                samples[insertion] = samples[insertion - 1];
                insertion--;
            }
            samples[insertion] = sample;
        }
    }

    private static long parseHttpDate(String header) {
        if (header == null || header.length() != 29 || header.charAt(3) != ','
                || header.charAt(4) != ' ' || header.charAt(7) != ' '
                || header.charAt(11) != ' ' || header.charAt(16) != ' '
                || header.charAt(19) != ':' || header.charAt(22) != ':'
                || header.charAt(25) != ' ' || !header.endsWith("GMT")) {
            return Long.MIN_VALUE;
        }
        int day = number(header, 5, 2);
        int month = month(header);
        int year = number(header, 12, 4);
        int hour = number(header, 17, 2);
        int minute = number(header, 20, 2);
        int second = number(header, 23, 2);
        if (day < 1 || month == 0 || year < 1 || hour < 0 || hour > 23 || minute < 0
                || minute > 59 || second < 0 || second > 59
                || day > daysInMonth(year, month)) {
            return Long.MIN_VALUE;
        }
        long days = daysBeforeYear(year) + daysBeforeMonth(year, month) + day - 1L
                - daysBeforeYear(1970);
        return (((days * 24L + hour) * 60L + minute) * 60L + second) * 1_000L;
    }

    private static int number(String text, int start, int digits) {
        int value = 0;
        for (int index = start; index < start + digits; index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return -1;
            }
            value = value * 10 + character - '0';
        }
        return value;
    }

    private static int month(String header) {
        return switch (header.substring(8, 11)) {
            case "Jan" -> 1;
            case "Feb" -> 2;
            case "Mar" -> 3;
            case "Apr" -> 4;
            case "May" -> 5;
            case "Jun" -> 6;
            case "Jul" -> 7;
            case "Aug" -> 8;
            case "Sep" -> 9;
            case "Oct" -> 10;
            case "Nov" -> 11;
            case "Dec" -> 12;
            default -> 0;
        };
    }

    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case 2 -> leapYear(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    private static boolean leapYear(int year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    private static long daysBeforeYear(int year) {
        long previous = year - 1L;
        return previous * 365L + previous / 4L - previous / 100L + previous / 400L;
    }

    private static long daysBeforeMonth(int year, int month) {
        int[] days = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        long before = days[month - 1];
        if (month > 2 && leapYear(year)) {
            before++;
        }
        return before;
    }

    private static long readStamp(byte[] packet, int at) {
        long seconds = readUnsigned32(packet, at);
        long fraction = readUnsigned32(packet, at + 4);
        long unixSeconds = (seconds & 0x8000_0000L) != 0L
                ? seconds - EPOCH_1900_TO_1970
                : seconds + (0x1_0000_0000L - EPOCH_1900_TO_1970);
        return unixSeconds * 1_000L + ((fraction * 1_000L) >>> 32);
    }

    private static void writeStamp(byte[] packet, int at, long millis) {
        long seconds = Math.floorDiv(millis, 1_000L) + EPOCH_1900_TO_1970;
        writeUnsigned32(packet, at, seconds);
    }

    private static long readUnsigned32(byte[] packet, int at) {
        return ((long) (packet[at] & 0xFF) << 24)
                | ((long) (packet[at + 1] & 0xFF) << 16)
                | ((long) (packet[at + 2] & 0xFF) << 8)
                | (packet[at + 3] & 0xFF);
    }

    private static void writeUnsigned32(byte[] packet, int at, long value) {
        packet[at] = (byte) (value >>> 24);
        packet[at + 1] = (byte) (value >>> 16);
        packet[at + 2] = (byte) (value >>> 8);
        packet[at + 3] = (byte) value;
    }

    private static boolean zero(byte[] packet, int at) {
        for (int index = at; index < at + 8; index++) {
            if (packet[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress[] resolve(String name) throws IOException {
        return InetAddress.getAllByName(name);
    }

    private static byte[] exchange(InetAddress address, byte[] request) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(address, PORT);
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[PACKET_BYTES];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return Arrays.copyOf(response, packet.getLength());
        }
    }
}
