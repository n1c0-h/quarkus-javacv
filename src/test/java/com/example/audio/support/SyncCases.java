package com.example.audio.support;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.audio.domain.models.AudioFormat;

/**
 * The synchronization business rule, written as a table covering every combination of formats the external
 * service can offer: {@code offered | stored as-is | transcoded target<source | duration measured on}.
 */
public final class SyncCases {

    private static final String[] TABLE = {
            "wav                    | -                    | mp3<wav, amr-wb<wav  | wav",
            "mp3                    | mp3                  | amr-wb<mp3           | mp3",
            "amr-nb                 | amr-nb               | mp3<amr-nb           | amr-nb",
            "amr-wb                 | amr-wb               | mp3<amr-wb           | amr-wb",
            "wav,mp3                | mp3                  | amr-wb<wav           | wav",
            "wav,amr-nb             | amr-nb               | mp3<wav              | wav",
            "wav,amr-wb             | amr-wb               | mp3<wav              | wav",
            "mp3,amr-nb             | mp3,amr-nb           | -                    | mp3",
            "mp3,amr-wb             | mp3,amr-wb           | -                    | mp3",
            "amr-nb,amr-wb          | amr-nb,amr-wb        | mp3<amr-wb           | amr-wb",
            "wav,mp3,amr-nb         | mp3,amr-nb           | -                    | mp3",
            "wav,mp3,amr-wb         | mp3,amr-wb           | -                    | mp3",
            "wav,amr-nb,amr-wb      | amr-nb,amr-wb        | mp3<wav              | wav",
            "mp3,amr-nb,amr-wb      | mp3,amr-nb,amr-wb    | -                    | mp3",
            "wav,mp3,amr-nb,amr-wb  | mp3,amr-nb,amr-wb    | -                    | mp3",
    };

    private SyncCases() {
    }

    public record Case(Set<AudioFormat> offered, Set<AudioFormat> stored, Map<AudioFormat, AudioFormat> transcodings,
            AudioFormat durationSource) {

        public Set<AudioFormat> resultFormats() {
            final EnumSet<AudioFormat> formats = EnumSet.noneOf(AudioFormat.class);
            formats.addAll(stored);
            formats.addAll(transcodings.keySet());
            return formats;
        }

        @Override
        @SuppressWarnings("NullableProblems") // never null; the project uses no @NotNull annotations
        public String toString() {
            return offered.stream().map(AudioFormat::getCode).collect(Collectors.joining("+"));
        }
    }

    public static Stream<Case> all() {
        return Arrays.stream(TABLE).map(SyncCases::parse);
    }

    private static Case parse(final String row) {
        final String[] columns = row.split("\\|");
        final Map<AudioFormat, AudioFormat> transcodings = new EnumMap<>(AudioFormat.class);
        for (final String entry : list(columns[2])) {
            final String[] parts = entry.split("<");
            transcodings.put(format(parts[0]), format(parts[1]));
        }
        return new Case(formats(columns[0]), formats(columns[1]), transcodings, format(columns[3].trim()));
    }

    private static Set<AudioFormat> formats(final String column) {
        final EnumSet<AudioFormat> formats = EnumSet.noneOf(AudioFormat.class);
        list(column).stream().map(SyncCases::format).forEach(formats::add);
        return formats;
    }

    private static List<String> list(final String column) {
        final String value = column.trim();
        return value.equals("-") ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static AudioFormat format(final String code) {
        return AudioFormat.fromCode(code.trim()).orElseThrow();
    }
}
