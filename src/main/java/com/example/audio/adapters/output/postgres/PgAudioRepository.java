package com.example.audio.adapters.output.postgres;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.InfrastructureException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.Md5Hash;
import com.example.audio.domain.models.VariantOrigin;
import com.example.audio.domain.ports.output.AudioRepository;

import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** {@link AudioRepository} on the reactive PostgreSQL client. Only prepared statements are used. */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class PgAudioRepository implements AudioRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String SELECT_BY_ID = """
            SELECT a.public_id, a.external_id, a.name, a.duration_ms, a.created_at, a.updated_at,
                   v.format::text AS format, v.origin::text AS origin, v.storage_key, v.size, v.md5
            FROM audio a LEFT JOIN audio_variant v ON v.audio_id = a.id
            WHERE a.public_id = $1
            ORDER BY v.format""";
    private static final String EXISTS_BY_EXTERNAL_ID = "SELECT 1 FROM audio WHERE external_id = $1";
    private static final String INSERT_AUDIO = """
            INSERT INTO audio (public_id, external_id, name, duration_ms, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6)
            RETURNING id""";
    private static final String INSERT_VARIANT = """
            INSERT INTO audio_variant (audio_id, format, origin, storage_key, size, md5)
            VALUES ($1, $2::audio_format, $3::variant_origin, $4, $5, $6)""";
    private static final String UPDATE_AUDIO = "UPDATE audio SET name = $2, updated_at = $3 WHERE public_id = $1";
    private static final String DELETE_AUDIO = "DELETE FROM audio WHERE public_id = $1";

    private final Pool pool;

    @Override
    public Uni<Optional<Audio>> findById(final AudioId id) {
        return pool.preparedQuery(SELECT_BY_ID).execute(Tuple.of(id.value())).map(PgAudioRepository::toAudio)
                .onFailure().transform(PgAudioRepository::translate);
    }

    @Override
    public Uni<Boolean> existsByExternalId(final ExternalAudioId externalId) {
        return pool.preparedQuery(EXISTS_BY_EXTERNAL_ID).execute(Tuple.of(externalId.value()))
                .map(rows -> rows.size() > 0)
                .onFailure().transform(PgAudioRepository::translate);
    }

    @Override
    public Uni<Void> insert(final Audio audio) {
        final Tuple row = Tuple.from(new Object[] { audio.id().value(), audio.externalId().value(), audio.name(),
                audio.duration().toMillis(), utc(audio.createdAt()), utc(audio.updatedAt()) });
        return pool.withTransaction(connection -> connection.preparedQuery(INSERT_AUDIO).execute(row)
                .map(rows -> rows.iterator().next().getLong("id"))
                .chain(id -> connection.preparedQuery(INSERT_VARIANT).executeBatch(audio.variants().stream()
                        .map(variant -> Tuple.from(new Object[] { id, variant.format().getCode(), variant.origin().name(),
                                variant.storageKey(), variant.size(), Buffer.buffer(variant.md5().bytes()) }))
                        .toList())))
                .replaceWithVoid()
                .onFailure(e -> e instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState()))
                .transform(e -> new AudioAlreadySynchronizedException("The external audio is already synchronized"))
                .onFailure().transform(PgAudioRepository::translate);
    }

    @Override
    public Uni<Void> update(final Audio audio) {
        return pool.preparedQuery(UPDATE_AUDIO)
                .execute(Tuple.of(audio.id().value(), audio.name(), utc(audio.updatedAt())))
                .replaceWithVoid()
                .onFailure().transform(PgAudioRepository::translate);
    }

    @Override
    public Uni<Boolean> delete(final AudioId id) {
        return pool.preparedQuery(DELETE_AUDIO).execute(Tuple.of(id.value())).map(rows -> rows.rowCount() > 0)
                .onFailure().transform(PgAudioRepository::translate);
    }

    /** One row per variant: the audio columns repeat on each of them. */
    private static Optional<Audio> toAudio(final RowSet<Row> rows) {
        Audio.AudioBuilder audio = null;
        for (final Row row : rows) {
            if (audio == null) {
                audio = Audio.builder()
                        .id(AudioId.of(row.getUUID("public_id")))
                        .externalId(ExternalAudioId.of(row.getUUID("external_id")))
                        .name(row.getString("name"))
                        .duration(Duration.ofMillis(row.getLong("duration_ms")))
                        .createdAt(row.getOffsetDateTime("created_at").toInstant())
                        .updatedAt(row.getOffsetDateTime("updated_at").toInstant());
            }
            if (row.getString("format") != null) {
                audio.variant(AudioVariant.builder()
                        .format(AudioFormat.fromCode(row.getString("format"))
                                .orElseThrow(() -> new InfrastructureException("Unknown stored format")))
                        .origin(VariantOrigin.valueOf(row.getString("origin")))
                        .storageKey(row.getString("storage_key"))
                        .size(row.getLong("size"))
                        .md5(Md5Hash.of(row.getBuffer("md5").getBytes()))
                        .build());
            }
        }
        return Optional.ofNullable(audio).map(Audio.AudioBuilder::build);
    }

    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Throwable translate(final Throwable t) {
        if (t instanceof DomainException) {
            return t;
        }
        log.error("Database operation failed", t);
        return new InfrastructureException("The database is unavailable", t);
    }
}
