package dev.openmap.client;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.openmap.draw.MarkerColour;
import dev.openmap.symbol.Affiliation;
import dev.openmap.symbol.SymbolIcon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;

final class Suggest {

    static List<String> COLOURS() {
        return ColoursHolder.COLOURS;
    }

    private static final class ColoursHolder {
        private static final List<String> COLOURS = lower(MarkerColour.values());
    }

    static List<String> AFFILIATIONS() {
        return AffiliationsHolder.AFFILIATIONS;
    }

    private static final class AffiliationsHolder {
        private static final List<String> AFFILIATIONS = lower(Affiliation.values());
    }

    static List<String> ICONS() {
        return IconsHolder.ICONS;
    }

    private static final class IconsHolder {
        private static final List<String> ICONS = lower(SymbolIcon.values());
    }

    private static final Map<String, String> FOLDED = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<String, String> QUOTABLE = Collections.synchronizedMap(new WeakHashMap<>());

    private Suggest() {
    }

    static SuggestionProvider<FabricClientCommandSource> of(
            Function<CommandContext<FabricClientCommandSource>,
                    Collection<String>> values) {
        return (context, builder) ->
                SharedSuggestionProvider.suggest(values.apply(context), builder);
    }

    static SuggestionProvider<FabricClientCommandSource> quoting(
            Function<CommandContext<FabricClientCommandSource>,
                    Collection<String>> values) {
        return (context, builder) -> {
            String lower = builder.getRemainingLowerCase();
            String matching = lower.startsWith("\"") ? lower.substring(1) : lower;
            for (String value : values.apply(context)) {
                if (matching.isEmpty()
                        || SharedSuggestionProvider.matchesSubStr(matching,
                                folded(value))) {
                    builder.suggest(quotable(value));
                }
            }
            return builder.buildFuture();
        };
    }

    static SuggestionProvider<FabricClientCommandSource> filtered(
            Function<CommandContext<FabricClientCommandSource>, Collection<String>> values) {
        return (context, builder) -> {
            String matching = builder.getRemainingLowerCase();
            for (String value : values.apply(context)) {
                if (SharedSuggestionProvider.matchesSubStr(matching, folded(value))
                        && ShareCommand.isDrawable(value)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    static SuggestionProvider<FabricClientCommandSource> cleaned(
            Function<CommandContext<FabricClientCommandSource>,
                    Collection<String>> values) {
        return (context, builder) -> {
            String matching = builder.getRemainingLowerCase();
            for (String value : values.apply(context)) {
                String cleanedValue = ShareCommand.drawable(value);
                if (SharedSuggestionProvider.matchesSubStr(matching,
                        cleanedValue.toLowerCase(Locale.ROOT))) {
                    builder.suggest(cleanedValue);
                }
            }
            return builder.buildFuture();
        };
    }

    static String quotable(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        String cached = QUOTABLE.get(value);
        if (cached != null) {
            return cached;
        }
        final int n = value.length();
        boolean plain = true;
        for (int i = 0; i < n; i++) {
            if (!StringReader.isAllowedInUnquotedString(value.charAt(i))) {
                plain = false;
                break;
            }
        }
        String result;
        if (plain) {
            result = value;
        } else {
            StringBuilder out = new StringBuilder().append('"');
            for (int i = 0; i < n; i++) {
                char c = value.charAt(i);
                if (c == '\\' || c == '"') {
                    out.append('\\');
                }
                out.append(c);
            }
            result = out.append('"').toString();
        }
        if (result != value) {
            QUOTABLE.put(value, result);
        }
        return result;
    }

    private static List<String> lower(Enum<?>[] values) {
        List<String> out = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            out.add(value.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static String folded(String value) {
        String cached = FOLDED.get(value);
        if (cached != null) {
            return cached;
        }
        String folded = value.toLowerCase(Locale.ROOT);
        if (folded != value) {
            FOLDED.put(value, folded);
        }
        return folded;
    }
}
