package dev.openmap.client;

import dev.openmap.config.LandNavConfig;
import dev.openmap.share.WorldPrint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class CollectorSettings {

    static final int WARN = 0xFFE0A33A;

    static final int MUTED = 0xFF9A9A9A;

    static final int GOOD = 0xFF88CC88;

    static final Component STATUS_DESCRIPTION =
            Component.translatable("openmap-collect.option.status.desc");

    static final Component STATUS_BUTTON =
            Component.translatable("openmap-collect.option.status.button");

    static final Component CLAIM_GREETINGS_GROUP_TITLE =
            Component.translatable("openmap-collect.config.group.claim_greetings");

    static final Component CLAIM_GREETINGS_LABEL =
            Component.translatable("openmap-collect.option.claim_greetings");

    static final Component CLAIM_GREETINGS_DESCRIPTION =
            Component.translatable("openmap-collect.option.claim_greetings.desc");

    private CollectorSettings() {
    }

    // servers: blank means every server.
    record Draft(String collector, boolean enabled, String servers, boolean claimGreetings) {
        Draft(String collector, boolean enabled, String servers) {
            this(collector, enabled, servers, false);
        }
    }

    // While held, or arming a cleartext address, writes only a withdrawal.
    static boolean write(LandNavConfig config, Draft draft, CollectorAddressCheck check) {
        String typed = draft.collector().trim();
        String stored = config.shareCollector == null
                ? "" : config.shareCollector.trim();
        boolean held = check.holds(stored, typed);
        boolean arming = draft.enabled() && !config.shareEnabled;
        List<String> keys = keysOf(draft.servers());
        boolean nowhere = namesNone(draft.servers(), keys);
        // approvedServers returns the live list.
        List<String> live = ChunkCapture.approvedServers(config);
        if (draft.enabled() && (held || (arming && ShareCommand.cleartextAwayFromHome(typed)))) {
            if (nowhere || narrows(live, keys)) {
                live.clear();
                live.addAll(keys);
                config.approvedServersConfigured = true;
                config.settleServerList();
            }
            return false;
        }
        if (!held) {
            config.shareCollector = typed;
        }
        config.shareEnabled = draft.enabled();
        config.claimGreetings = draft.claimGreetings();
        live.clear();
        live.addAll(keys);
        config.approvedServersConfigured = !keys.isEmpty() || nowhere;
        config.settleServerList();
        return !held;
    }

    static void write(LandNavConfig config, Draft draft) {
        CollectorAddressCheck check = CollectorOptions.newAddressCheck(config);
        check.trusted(draft.collector());
        write(config, draft, check);
    }

    private static boolean namesNone(String servers, List<String> keys) {
        return keys.isEmpty() && !droppedKeys(servers).isEmpty();
    }

    private static boolean narrows(List<String> live, List<String> keys) {
        return !keys.isEmpty()
                && (live.isEmpty() || (keys.size() < live.size() && live.containsAll(keys)));
    }

    static List<String> keysOf(String servers) {
        Set<String> keys = new LinkedHashSet<>();
        for (String piece : servers.split(",")) {
            String host = ChunkCapture.serverKey(piece);
            if (!host.isEmpty()) {
                keys.add(ChunkCapture.serverWorldKey(piece));
            }
        }
        return new ArrayList<>(keys);
    }

    static List<String> droppedKeys(String servers) {
        List<String> dropped = new ArrayList<>();
        for (String piece : servers.split(",")) {
            if (piece.isBlank()) {
                continue;
            }
            if (ChunkCapture.serverKey(piece).isEmpty()) {
                dropped.add(piece.trim());
            }
        }
        return dropped;
    }

    enum Where { ALL, LISTED }

    static Where whereOf(List<String> keys) {
        return keys.isEmpty() ? Where.ALL : Where.LISTED;
    }

    record Status(Component text, int colour) {
    }

    private static Component whereComponent(Where whereState, List<String> keys) {
        return switch (whereState) {
            case ALL -> Component.translatable("openmap-collect.status.gate_all");
            case LISTED -> Component.translatable("openmap-collect.status.gate_listed",
                    String.join(", ", keys));
        };
    }

    static Status status(Draft draft) {
        String address = draft.collector().trim();
        if (address.isEmpty()) {
            return new Status(Component.translatable("openmap-collect.status.no_address"), WARN);
        }
        if (!ShareCommand.looksUsable(address)) {
            return new Status(Component.translatable("openmap-collect.status.not_an_address"), WARN);
        }
        if (ShareCommand.cleartextAwayFromHome(address)) {
            return new Status(Component.translatable("openmap-collect.status.cleartext"), WARN);
        }
        List<String> keys = keysOf(draft.servers());
        if (!draft.enabled() || namesNone(draft.servers(), keys)) {
            return new Status(Component.translatable("openmap-collect.status.off"), MUTED);
        }
        Component where = whereComponent(whereOf(keys), keys);
        MutableComponent line = Component.translatable("openmap-collect.status.sending", address)
                .append("; ").append(where);
        return new Status(line, GOOD);
    }

    // The steps as last read; one reader at a time.
    static final class Setup {

        private static final int HTTP_STATUS_CLASS = 100;

        private static final int HTTP_SUCCESS_CLASS = 2;

        private static final int HTTP_REDIRECT_CLASS = 3;

        static final String CHAT_PREFIX = "[GeoSurvey] ";

        private static final String MISSING_HEADING = "Ground does not reach"
                + " the collector.";

        private static final String HEADING = "Steps for ground to reach the collector:";

        private static final int LINES_CAPACITY = 8;

        private static final int NAMES_LISTED = 8;

        enum Address { SET, BLANK, UNUSABLE }

        enum Switch { ON, OFF, NOT_LISTED, NO_SERVER }

        enum Key { MOJANG, ACCEPTED, UNPROVEN, REFUSED, UNROUTED, ASKING, NONE }

        // NONE: the collector wants no walk.
        enum Walk { WALKED, OWED, ASKING, NONE }

        enum Upload { ACCEPTED, REFUSED, UNREACHED, TRYING, NONE }

        // The config text the next two come from.
        private String addressOf;

        private String address = "";

        private boolean usable;

        private Address addressStep = Address.BLANK;

        private Switch switchStep = Switch.OFF;

        private Key keyStep = Key.NONE;

        private Walk walkStep = Walk.ASKING;

        private Upload uploadStep = Upload.NONE;

        // Read only when resolving.
        private String joined;

        private String foundFor = "";

        private String enrolReason = "";

        private String fingerprint = "";

        private List<WorldPrint.Region> owed = List.of();

        private String refusal = "";

        private String trouble = "";

        private int lastStatus;

        // Client thread only; sender may be null.
        static Setup now(LandNavConfig config, ShareSender sender) {
            Setup setup = new Setup();
            String joined = ChunkCapture.joinedServer();
            setup.read(config, sender, joined, ChunkCapture.approved(config, joined), true);
            return setup;
        }

        // sender and joined may be null.
        void read(LandNavConfig config, ShareSender sender, String joined, boolean listed,
                boolean resolve) {
            String typed = config.shareCollector;
            if (typed != addressOf) {
                addressOf = typed;
                address = typed == null ? "" : typed.trim();
                usable = !address.isEmpty() && ShareCommand.looksUsable(address);
            }
            addressStep = address.isEmpty() ? Address.BLANK
                    : usable ? Address.SET : Address.UNUSABLE;
            switchStep = switchOf(config, joined, listed);
            boolean reaching = addressStep == Address.SET && switchStep == Switch.ON
                    && sender != null;
            uploadStep = reaching ? uploadOf(sender) : Upload.NONE;
            keyStep = reaching ? keyOf(config, sender, resolve, uploadStep == Upload.ACCEPTED)
                    : Key.NONE;
            walkStep = reaching ? walkOf(sender) : Walk.ASKING;
            if (resolve) {
                resolve(sender, joined);
            }
        }

        boolean missing() {
            return addressStep != Address.SET
                    || switchStep == Switch.OFF || switchStep == Switch.NOT_LISTED
                    || keyStep == Key.UNPROVEN || keyStep == Key.REFUSED || keyStep == Key.UNROUTED
                    || walkStep == Walk.OWED
                    || uploadStep == Upload.REFUSED || uploadStep == Upload.UNREACHED;
        }

        // After a resolving read.
        List<String> lines() {
            List<String> out = new ArrayList<>(LINES_CAPACITY);
            out.add(addressLine());
            out.add(switchLine());
            if (addressStep != Address.SET || switchStep != Switch.ON) {
                out.add(switchStep == Switch.NO_SERVER
                        ? "Not yet: the key, walk and uploads start on a server."
                        : "Not yet: the key, walk and uploads follow the"
                                + " steps above.");
            } else {
                out.add(keyLine());
                String walked = walkLine();
                if (!walked.isEmpty()) {
                    out.add(walked);
                }
                out.add(uploadLine());
            }
            for (int at = 0; at < out.size(); at++) {
                out.set(at, ShareCommand.drawable(out.get(at)));
            }
            return out;
        }

        List<String> said() {
            List<String> steps = lines();
            List<String> out = new ArrayList<>(steps.size() + 1);
            out.add(CHAT_PREFIX + (missing() ? MISSING_HEADING : HEADING));
            for (String step : steps) {
                out.add(CHAT_PREFIX + step);
            }
            return out;
        }

        Component statusLine() {
            Component line;
            if (addressStep == Address.BLANK) {
                line = Component.translatable("openmap-collect.status.no_address");
            } else if (addressStep == Address.UNUSABLE) {
                line = Component.translatable("openmap-collect.status.not_an_address");
            } else if (switchStep == Switch.OFF) {
                line = Component.translatable("openmap-collect.status.off");
            } else if (switchStep == Switch.NOT_LISTED) {
                line = Component.translatable("openmap-collect.status.not_listed");
            } else if (switchStep == Switch.NO_SERVER) {
                line = Component.translatable("openmap-collect.status.sending", address);
            } else if (keyStep == Key.UNPROVEN) {
                line = Component.translatable("openmap-collect.status.key_unproven");
            } else if (keyStep == Key.REFUSED || keyStep == Key.UNROUTED) {
                line = Component.translatable("openmap-collect.status.key_refused");
            } else if (walkStep == Walk.OWED) {
                line = Component.translatable("openmap-collect.status.walk");
            } else if (uploadStep == Upload.REFUSED) {
                line = Component.translatable("openmap-collect.status.refused");
            } else if (uploadStep == Upload.UNREACHED) {
                line = Component.translatable("openmap-collect.status.unreached");
            } else if (keyStep == Key.NONE || keyStep == Key.ASKING || walkStep == Walk.ASKING
                    || uploadStep == Upload.NONE || uploadStep == Upload.TRYING) {
                line = Component.translatable("openmap-collect.status.waiting", address);
            } else {
                line = Component.translatable("openmap-collect.status.sending", address);
            }
            return line;
        }

        private String addressLine() {
            return switch (addressStep) {
                case SET -> "Done: the collector address is " + address
                        + (foundFor.isEmpty() ? ", set by hand." : ", found for " + foundFor + ".");
                case BLANK -> "Missing: a collector address. Set one with /geosurvey"
                        + " collector <address>, or join a known"
                        + " server: "
                        + LandNavConfig.KnownServers.names() + ".";
                case UNUSABLE -> "Missing: the collector address " + address + " must start with"
                        + " http:// or https://. Set another with /geosurvey collector"
                        + " <address>.";
            };
        }

        private String switchLine() {
            String host = joined == null ? "" : ChunkCapture.serverKey(joined);
            return switch (switchStep) {
                case ON -> "Done: contribute ground is on.";
                case OFF -> "Missing: contribute ground is off. Run /geosurvey share"
                        + " on.";
                case NOT_LISTED -> "Missing: this server is not approved. Run"
                        + " /geosurvey server add " + (host.isEmpty() ? "<ip>" : host)
                        + ".";
                case NO_SERVER -> "Done: contribute ground is on. Ground goes up from servers"
                        + " only.";
            };
        }

        private String keyLine() {
            return switch (keyStep) {
                case MOJANG -> "Done: your Mojang profile key signs uploads.";
                case ACCEPTED -> "Done: the collector accepts"
                        + " this computer's own key.";
                case UNPROVEN -> "Missing: the collector does not accept this computer's own"
                        + " key. Run"
                        + " /geosurvey share proof on; it sends your player name to the collector"
                        + " and to Mojang.";
                case REFUSED -> "Missing: the collector refused this computer's own"
                        + " key; restart to ask again." + trust();
                case UNROUTED -> "Missing: the collector cannot accept this computer's"
                        + " own key." + trust();
                case ASKING -> "Not yet: asking the collector to accept this"
                        + " computer's own key" + why(enrolReason);
                case NONE -> "Not yet: this client has no signing key.";
            };
        }

        private String trust() {
            return " Its operator can trust fingerprint " + fingerprint
                    + ".";
        }

        private String walkLine() {
            return switch (walkStep) {
                case WALKED -> "Done: the requested walk.";
                case OWED -> "Missing: a walk to the collector's regions: " + regionNames()
                        + ". Uploads wait for it.";
                case ASKING -> "Not yet: asking the collector whether it wants"
                        + " a walk.";
                case NONE -> "";
            };
        }

        private String regionNames() {
            StringBuilder names = new StringBuilder();
            int shown = Math.min(owed.size(), NAMES_LISTED);
            for (int at = 0; at < shown; at++) {
                if (at > 0) {
                    names.append(", ");
                }
                names.append(owed.get(at).name());
            }
            if (owed.size() > shown) {
                names.append(" and ").append(owed.size() - shown).append(" more");
            }
            return names.toString();
        }

        private String uploadLine() {
            return switch (uploadStep) {
                case ACCEPTED -> "Done: the collector accepted the last upload.";
                case REFUSED -> refusal.isEmpty() ? "Missing: the collector refused an upload."
                        : "Missing: " + Character.toLowerCase(refusal.charAt(0))
                                + refusal.substring(1);
                case UNREACHED -> "Missing: no upload reached the collector"
                        + (trouble.isEmpty() ? "." : ": " + trouble + ".");
                case TRYING -> "Not yet: retrying the last upload"
                        + (trouble.isEmpty() ? "; the collector answered " + lastStatus + "."
                                : ": " + trouble + ".");
                case NONE -> "Not yet: no upload was answered.";
            };
        }

        private static String why(String reason) {
            String said;
            if (reason.isEmpty()) {
                said = ".";
            } else if (reason.endsWith(".")) {
                said = ". " + reason;
            } else {
                said = " (" + reason + ").";
            }
            return said;
        }

        // sender: not null when a step names it.
        private void resolve(ShareSender sender, String joinedServer) {
            joined = joinedServer;
            foundFor = addressStep == Address.SET ? foundFor(address, joinedServer) : "";
            enrolReason = keyStep == Key.ASKING ? sender.enrolmentReason() : "";
            fingerprint = keyStep == Key.REFUSED || keyStep == Key.UNROUTED
                    ? sender.identityFingerprint() : "";
            owed = walkStep == Walk.OWED ? sender.groundOwed() : List.of();
            refusal = uploadStep == Upload.REFUSED ? sender.lastRefusal() : "";
            boolean troubled = uploadStep == Upload.UNREACHED || uploadStep == Upload.TRYING;
            trouble = troubled ? sender.groundReason() : "";
            lastStatus = troubled ? sender.lastStatus() : 0;
        }

        private static String foundFor(String address, String joined) {
            String found = "";
            String here = ChunkCapture.knownCollector(joined);
            if (!here.isEmpty() && here.equals(address)) {
                found = ChunkCapture.serverKey(joined);
            } else {
                String[] names = LandNavConfig.KnownServers.names().split(", ");
                for (int at = 0; at < names.length && found.isEmpty(); at++) {
                    if (address.equals(LandNavConfig.KnownServers.collectorOf(names[at]))) {
                        found = names[at];
                    }
                }
            }
            return found;
        }

        private static Switch switchOf(LandNavConfig config, String joined, boolean listed) {
            Switch turned;
            if (!config.shareEnabled) {
                turned = Switch.OFF;
            } else if (joined == null) {
                turned = Switch.NO_SERVER;
            } else if (!listed) {
                turned = Switch.NOT_LISTED;
            } else {
                turned = Switch.ON;
            }
            return turned;
        }

        private static Key keyOf(LandNavConfig config, ShareSender sender, boolean resolve,
                boolean uploadAccepted) {
            String reason = sender.enrolmentReason();
            Key key;
            if (!sender.signing()) {
                key = Key.NONE;
            } else if (!sender.localIdentity()) {
                key = Key.MOJANG;
            } else if (uploadAccepted) {
                key = Key.ACCEPTED;
            } else if (!config.shareSessionProof) {
                key = Key.UNPROVEN;
            } else if (ShareSender.ENROL_REFUSED.equals(reason)) {
                key = Key.REFUSED;
            } else if (ShareSender.ENROL_UNROUTED.equals(reason)) {
                key = Key.UNROUTED;
            } else if (resolve && sender.enrolledHere()) {
                key = Key.ACCEPTED;
            } else {
                key = Key.ASKING;
            }
            return key;
        }

        private static Walk walkOf(ShareSender sender) {
            Walk walk;
            if (!sender.groundAsked()) {
                walk = Walk.ASKING;
            } else if (sender.groundAskedNothing()) {
                walk = Walk.NONE;
            } else if (sender.groundProven()) {
                walk = Walk.WALKED;
            } else if (sender.groundAskOpen()) {
                walk = Walk.ASKING;
            } else {
                walk = Walk.OWED;
            }
            return walk;
        }

        private static Upload uploadOf(ShareSender sender) {
            int status = sender.lastStatus();
            Upload upload;
            if (!sender.lastRefusal().isEmpty()) {
                upload = Upload.REFUSED;
            } else if (sender.uploadUnreached()) {
                upload = sender.sent() == 0L ? Upload.UNREACHED : Upload.TRYING;
            } else if (status / HTTP_STATUS_CLASS == HTTP_SUCCESS_CLASS) {
                upload = Upload.ACCEPTED;
            } else if (status / HTTP_STATUS_CLASS == HTTP_REDIRECT_CLASS) {
                upload = Upload.UNREACHED;
            } else if (status != 0) {
                upload = Upload.TRYING;
            } else {
                upload = Upload.NONE;
            }
            return upload;
        }
    }
}
