package com.example.audio.domain.ports.input;

import java.io.InputStream;

import com.example.audio.domain.models.AudioContent;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.FormatSelector;

/**
 * Audio bytes, streamed. Everything that can fail is checked before the returned content is written. Blocking.
 */
public interface AudioContentUseCase {

    AudioContent download(AudioId id, FormatSelector format);

    /**
     * Plays a stored audio inside a configured template. The output has the format of the selected input variant;
     * fixed sounds are taken in that same format.
     */
    AudioContent concatenate(AudioId id, FormatSelector format, String template);

    /** Same as {@link #concatenate(AudioId, FormatSelector, String)} for an uploaded audio, read while the output is written. */
    AudioContent concatenate(InputStream input, AudioFormat format, String template);

    /** The input is read while the output is written. */
    AudioContent transcode(InputStream input, AudioFormat source, FormatSelector target);
}
