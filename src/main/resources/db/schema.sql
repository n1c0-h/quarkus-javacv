CREATE TYPE audio_format AS ENUM ('mp3', 'amr-nb', 'amr-wb');

CREATE TYPE variant_origin AS ENUM ('DOWNLOADED', 'TRANSCODED');

CREATE TABLE audio (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- identifier exposed in the API and in storage keys
    public_id   uuid         NOT NULL UNIQUE,
    external_id uuid         NOT NULL UNIQUE,
    name        varchar(255) NOT NULL,
    duration_ms bigint       NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE audio_variant (
    audio_id    bigint         NOT NULL REFERENCES audio (id) ON DELETE CASCADE,
    format      audio_format   NOT NULL,
    origin      variant_origin NOT NULL,
    storage_key varchar(512)   NOT NULL,
    size        bigint         NOT NULL,
    md5         bytea          NOT NULL CHECK (octet_length(md5) = 16),
    PRIMARY KEY (audio_id, format)
);
