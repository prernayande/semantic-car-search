CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE car (
  id                 SERIAL PRIMARY KEY,
  make               TEXT NOT NULL,
  model              TEXT NOT NULL,
  year               SMALLINT NOT NULL,
  fuel_type_raw      TEXT,
  fuel               TEXT,            -- gasoline | diesel | electric | flex | natural_gas
  engine_hp          INT,
  engine_cylinders   SMALLINT,
  transmission       TEXT,            -- AUTOMATIC | MANUAL | AUTOMATED_MANUAL | DIRECT_DRIVE
  drivetrain         TEXT,            -- fwd | rwd | awd | 4wd
  doors              SMALLINT,
  market_categories  TEXT[] NOT NULL DEFAULT '{}',
  -- space-joined copy of market_categories: array_to_string() is not IMMUTABLE,
  -- so the generated tsvector below cannot call it directly
  market_text        TEXT NOT NULL DEFAULT '',
  vehicle_size       TEXT,            -- Compact | Midsize | Large
  vehicle_style      TEXT,
  body_type          TEXT,
  highway_mpg        SMALLINT,
  city_mpg           SMALLINT,
  popularity         INT,
  msrp               INT,
  price_known        BOOLEAN NOT NULL DEFAULT true,
  search_text        TEXT NOT NULL,
  search_tsv         tsvector GENERATED ALWAYS AS (
      setweight(to_tsvector('english', make || ' ' || model), 'A') ||
      setweight(to_tsvector('english', coalesce(vehicle_style, '') || ' ' || coalesce(body_type, '') || ' ' ||
                market_text), 'B') ||
      setweight(to_tsvector('english', coalesce(fuel, '') || ' ' || coalesce(drivetrain, '') || ' ' ||
                coalesce(vehicle_size, '')), 'C')
  ) STORED,
  embedding          vector(384) NOT NULL
);

CREATE INDEX car_embedding_hnsw ON car USING hnsw (embedding vector_cosine_ops);
CREATE INDEX car_tsv_gin        ON car USING gin (search_tsv);
CREATE INDEX car_body_type      ON car (body_type);
CREATE INDEX car_fuel           ON car (fuel);
CREATE INDEX car_msrp           ON car (msrp);
CREATE INDEX car_make_year      ON car (make, year);

CREATE TABLE dataset_meta (
  id               SMALLINT PRIMARY KEY DEFAULT 1,
  embedding_model  TEXT NOT NULL,
  dimensions       SMALLINT NOT NULL,
  row_count        INT NOT NULL,
  ingested_at      TIMESTAMPTZ NOT NULL
);
