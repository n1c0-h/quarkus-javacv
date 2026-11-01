package com.example.audio.domain.services;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.SyncCases;

class VariantPolicyTest {

    private final VariantPolicy policy = new VariantPolicy();

    static Stream<SyncCases.Case> cases() {
        return SyncCases.all();
    }

    @ParameterizedTest(name = "offered {0}")
    @MethodSource("cases")
    void plansEveryCombinationOfOfferedFormats(final SyncCases.Case expected) {
        final SyncPlan plan = policy.plan(expected.offered());

        Assertions.assertThat(plan.stored()).isEqualTo(expected.stored());
        Assertions.assertThat(plan.transcodings()).isEqualTo(expected.transcodings());
        Assertions.assertThat(plan.durationSource()).isEqualTo(expected.durationSource());
    }

    @ParameterizedTest(name = "offered {0}")
    @MethodSource("cases")
    void alwaysEndsWithMp3AndOneAmrVariantAndNeverWav(final SyncCases.Case expected) {
        final Set<AudioFormat> formats = expected.resultFormats();

        Assertions.assertThat(formats).contains(AudioFormat.MP3).doesNotContain(AudioFormat.WAV)
                .containsAnyOf(AudioFormat.AMR_NB, AudioFormat.AMR_WB);
    }

    @ParameterizedTest(name = "offered {0}")
    @MethodSource("cases")
    void neverDownloadsAFormatThatIsNotUsed(final SyncCases.Case expected) {
        final SyncPlan plan = policy.plan(expected.offered());

        final Set<AudioFormat> used = new HashSet<>(plan.stored());
        used.addAll(plan.transcodings().values());
        Assertions.assertThat(plan.fetched()).isEqualTo(used);
    }

    @Test
    void rejectsAnAudioWithoutSupportedFormat() {
        Assertions.assertThatThrownBy(() -> policy.plan(Set.of())).isInstanceOf(InvalidRequestException.class);
    }
}
