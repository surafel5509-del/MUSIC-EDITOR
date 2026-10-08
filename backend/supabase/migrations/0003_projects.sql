-- Projects: document store + denormalized metadata for listings.
create table public.projects (
  id              uuid primary key default gen_random_uuid(),
  owner_id        uuid references auth.users(id) on delete cascade, -- null = guest upload later
  name            text not null default 'Untitled',
  template        text not null default 'EMPTY',
  bpm             double precision not null default 120,
  key_name        text,
  time_signature  text not null default '4/4',
  sample_rate     int not null default 48000,
  track_count     int not null default 0,
  duration_bars   double precision not null default 64,
  document        jsonb not null default '{}'::jsonb,   -- canonical CRDT-merged doc
  document_version bigint not null default 0,           -- CAS version for upserts
  lamport         bigint not null default 0,            -- highest applied op stamp
  artwork_path    text,
  genre           text,
  is_archived     boolean not null default false,
  is_deleted      boolean not null default false,       -- soft delete (GDPR purge job hardens)
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  last_opened_at  timestamptz
);

create index projects_owner_updated on public.projects (owner_id, updated_at desc);
create index projects_name_trgm on public.projects using gin (name gin_trgm_ops);
create index projects_document_gin on public.projects using gin (document jsonb_path_ops);

create trigger projects_touch_updated before update on public.projects
  for each row execute function public.touch_updated_at();

-- Collaboration ops (the CRDT wire log).
create table public.ops (
  op_id       uuid primary key,
  project_id  uuid not null references public.projects(id) on delete cascade,
  author_id   uuid not null,
  lamport     bigint not null,
  wall_clock  timestamptz not null default now(),
  payload     jsonb not null,
  created_at  timestamptz not null default now()
) partition by range (created_at);

-- Hot partitions (created monthly by pg_cron; see 0009_partitioning.sql).
create table public.ops_default partition of public.ops default;

create index ops_project_lamport on public.ops (project_id, lamport);

-- Shares & collaborators.
create table public.project_members (
  project_id  uuid not null references public.projects(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  role        text not null default 'COMMENTER' check (role in ('OWNER','EDITOR','COMMENTER','VIEWER')),
  joined_at   timestamptz not null default now(),
  primary key (project_id, user_id)
);

create table public.share_links (
  token        text primary key default encode(gen_random_bytes(12), 'hex'),
  project_id   uuid not null references public.projects(id) on delete cascade,
  role         text not null default 'COMMENTER',
  fork_on_accept boolean not null default false,
  expires_at   timestamptz,
  max_uses     int,
  use_count    int not null default 0,
  created_by   uuid not null references auth.users(id),
  created_at   timestamptz not null default now()
);

-- Timeline comments.
create table public.comments (
  id           uuid primary key default gen_random_uuid(),
  project_id   uuid not null references public.projects(id) on delete cascade,
  author_id    uuid not null references auth.users(id) on delete cascade,
  parent_id    uuid references public.comments(id) on delete cascade,
  anchor_frame bigint,
  anchor_track_id text,
  selection_start bigint,
  selection_end bigint,
  text         text not null check (char_length(text) between 1 and 2000),
  resolved     boolean not null default false,
  created_at   timestamptz not null default now()
);

create index comments_project on public.comments (project_id, created_at);

-- Version snapshots (metadata only; documents live in the projects bucket).
create table public.project_versions (
  id           uuid primary key default gen_random_uuid(),
  project_id   uuid not null references public.projects(id) on delete cascade,
  label        text not null,
  document_version bigint not null,
  created_by   uuid,
  storage_path text not null,
  size_bytes   bigint not null default 0,
  is_autosave  boolean not null default false,
  created_at   timestamptz not null default now()
);

create index versions_project on public.project_versions (project_id, created_at desc);
