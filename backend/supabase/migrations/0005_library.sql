-- Sound library catalog + user sample registry.
create table public.library_items (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  category     text not null check (category in ('LOOPS','ONE_SHOTS','MIDI_PACKS','PRESETS','INSTRUMENT_PACKS','FX_PACKS')),
  genre        text,
  mood         text,
  bpm          double precision,
  key_name     text,
  is_loop      boolean not null default false,
  duration_frames bigint not null default 0,
  sample_rate  int not null default 44100,
  format       text not null default 'WAV',
  preview_path text not null,             -- media bucket, low-bitrate preview
  full_path    text not null,             -- packs bucket, full quality
  artwork_path text,
  pack_id      uuid,
  is_premium   boolean not null default false,
  license_kind text not null default 'ROYALTY_FREE',
  tags         text[] default '{}',
  upload_count int not null default 0,
  loudness_lufs real,
  created_at   timestamptz not null default now()
);

create index library_category on public.library_items (category, created_at desc);
create index library_genre_bpm on public.library_items (genre, bpm);
create index library_name_trgm on public.library_items using gin (name gin_trgm_ops);
create index library_tags on public.library_items using gin (tags);
-- FTS across name+tags for the search endpoint.
create index library_fts on public.library_items
  using gin (to_tsvector('english', name || ' ' || coalesce(array_to_string(tags, ' '), '')));

create table public.sample_packs (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  description text not null default '',
  curator     text not null default 'StudioOne',
  category    text not null default 'LOOPS',
  genre       text,
  item_count  int not null default 0,
  size_bytes  bigint not null default 0,
  artwork_path text,
  price_micros bigint not null default 0,
  product_id  text,                       -- Play Billing product when paid
  is_premium_only boolean not null default false,
  rating      real not null default 0,
  created_at  timestamptz not null default now()
);

alter table public.library_items
  add constraint library_pack_fk foreign key (pack_id) references public.sample_packs(id) on delete set null;

-- User-imported samples registry (files live in device storage or the
-- projects bucket when a project syncs).
create table public.user_samples (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null references auth.users(id) on delete cascade,
  project_id  uuid references public.projects(id) on delete cascade,
  name        text not null,
  storage_path text,
  format      text not null default 'WAV',
  sample_rate int not null default 48000,
  channels    int not null default 2,
  duration_frames bigint not null default 0,
  size_bytes  bigint not null default 0,
  bpm         double precision,
  key_name    text,
  origin      text not null default 'IMPORTED' check (origin in ('RECORDED','IMPORTED','LIBRARY','BOUNCE','RENDER')),
  created_at  timestamptz not null default now()
);

create index user_samples_owner on public.user_samples (owner_id, created_at desc);

-- Presets (fx chains & instrument patches) — factory rows seeded, user rows RLS'd.
create table public.presets (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  plugin_id    text,
  instrument_id text,
  params       jsonb not null default '{}',
  chain        jsonb,
  author_id    uuid references auth.users(id) on delete cascade,
  is_factory   boolean not null default false,
  tags         text[] default '{}',
  download_count int not null default 0,
  created_at   timestamptz not null default now()
);

create index presets_plugin on public.presets (plugin_id, is_factory desc);
create index presets_instrument on public.presets (instrument_id, is_factory desc);
