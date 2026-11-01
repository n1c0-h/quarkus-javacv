package com.example.audio.application.usecases;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.example.audio.application.config.ConcatenationConfig;
import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioContent;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ConcatenationTemplate;
import com.example.audio.domain.models.FormatSelector;
import com.example.audio.domain.models.SoundName;
import com.example.audio.domain.models.StorageKeys;
import com.example.audio.domain.models.TemplatePart;
import com.example.audio.domain.ports.input.AudioContentUseCase;
import com.example.audio.domain.ports.output.AudioProcessor;
import com.example.audio.domain.ports.output.AudioRepository;
import com.example.audio.domain.ports.output.AudioStorage;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

/** Started eagerly: an invalid configured template prevents the application from starting. */
@Startup
@ApplicationScoped
public class AudioContentService implements AudioContentUseCase {

    private final AudioRepository audioRepository;
    private final AudioStorage audioStorage;
    private final AudioProcessor audioProcessor;
    private final Map<String, ConcatenationTemplate> templates;

    AudioContentService(final AudioRepository audioRepository, final AudioStorage audioStorage, final AudioProcessor audioProcessor,
            final ConcatenationConfig config) {
        this.audioRepository = audioRepository;
        this.audioStorage = audioStorage;
        this.audioProcessor = audioProcessor;
        this.templates = config.templates().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> ConcatenationTemplate.parse(entry.getValue())));
    }

    @Override
    public AudioContent download(final AudioId id, final FormatSelector format) {
        final AudioVariant variant = variant(AudioLibraryService.find(audioRepository, id).await().indefinitely(), format);
        return AudioContent.of(variant.format(), variant.size(), output -> {
            try (InputStream input = audioStorage.download(variant.storageKey())) {
                input.transferTo(output);
            }
        });
    }

    @Override
    public AudioContent concatenate(final AudioId id, final FormatSelector format, final String template) {
        final AudioVariant input = variant(AudioLibraryService.find(audioRepository, id).await().indefinitely(), format);
        return concatenation(template(template), input.format(), () -> audioStorage.download(input.storageKey()));
    }

    @Override
    public AudioContent concatenate(final InputStream input, final AudioFormat format, final String template) {
        if (!format.isProposed()) {
            throw new InvalidRequestException("Fixed sounds are only available in mp3, amr-nb and amr-wb");
        }
        final InputStream checked = checkedSignature(input, format);
        return concatenation(template(template), format, () -> checked);
    }

    private ConcatenationTemplate template(final String name) {
        final ConcatenationTemplate template = templates.get(name);
        if (template == null) {
            throw new InvalidRequestException("Unknown template '%s'".formatted(name));
        }
        return template;
    }

    /** Every fixed sound is checked before anything is written, so a missing one is a clean 404. */
    private AudioContent concatenation(final ConcatenationTemplate template, final AudioFormat format,
            final Supplier<InputStream> input) {
        for (final SoundName name : template.fixedSounds()) {
            if (!Boolean.TRUE.equals(audioStorage.exists(StorageKeys.fixedSound(name, format)).await().indefinitely())) {
                throw new AudioNotFoundException("Fixed sound '%s' is not available in %s".formatted(name, format.getCode()));
            }
        }
        final List<Supplier<InputStream>> parts = template.parts().stream()
                .<Supplier<InputStream>>map(part -> switch (part) {
                    case TemplatePart.Input ignored -> input;
                    case TemplatePart.FixedSound(final SoundName name) -> () -> audioStorage.download(StorageKeys.fixedSound(name, format));
                })
                .toList();
        // a template contains exactly one input
        final int inputIndex = template.parts().indexOf(new TemplatePart.Input());
        return AudioContent.ofUnknownLength(format, output -> audioProcessor.concatenate(parts, inputIndex, format, output));
    }

    @Override
    public AudioContent transcode(final InputStream input, final AudioFormat source, final FormatSelector target) {
        final AudioFormat format = target.transcodingTarget();
        final InputStream checked = checkedSignature(input, source);
        return AudioContent.ofUnknownLength(format, output -> audioProcessor.process(checked, source, Map.of(format, output)));
    }

    /** Rejects content that does not match its declared format before anything is sent back. */
    private static InputStream checkedSignature(final InputStream input, final AudioFormat format) {
        try {
            final PushbackInputStream stream = new PushbackInputStream(input, AudioFormat.SIGNATURE_LENGTH);
            final byte[] head = stream.readNBytes(AudioFormat.SIGNATURE_LENGTH);
            if (!format.matchesSignature(head)) {
                throw new InvalidAudioException("The content is not a valid " + format.getCode() + " audio");
            }
            stream.unread(head);
            return stream;
        } catch (IOException e) {
            throw new InvalidAudioException("The uploaded audio could not be read", e);
        }
    }

    private static AudioVariant variant(final Audio audio, final FormatSelector format) {
        return audio.variant(format)
                .orElseThrow(() -> new AudioNotFoundException("The audio is not available in " + format.getCode()));
    }
}
