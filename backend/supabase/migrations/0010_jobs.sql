-- Async job tables for analysis and server-side export rendering.
create table public.analysis_jobs (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  storage_path text not null,
  status       text not null default 'QUEUED' check (status in ('QUEUED','RUNNING','DONE','FAILED')),
  bpm          double precision,
  key_name     text,
  loudness_lufs real,
  error        text,
  created_at   timestamptz not null default now(),
  finished_at  timestamptz
);

create table public.export_jobs (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  project_id     uuid not null references public.projects(id) on delete cascade,
  kind           text not null default 'MIXDOWN',
  format         text not null,
  sample_rate    int not null default 48000,
  bit_depth      text not null default 'PCM_24',
  bitrate_kbps   int not null default 320,
  loudness_target real,
  metadata       jsonb not null default '{}',
  status         text not null default 'QUEUED' check (status in ('QUEUED','RENDERING','ENCODING','DONE','FAILED')),
  output_path    text,
  error          text,
  created_at     timestamptz not null default now(),
  finished_at    timestamptz
);

alter table public.analysis_jobs enable row level security;
alter table public.export_jobs enable row level security;
create policy analysis_own on public.analysis_jobs for select using (user_id = auth.uid());
create policy export_own on public.export_jobs for select using (user_id = auth.uid());

create index export_jobs_status on public.export_jobs (status, created_at);

-- Helper used by push-ops to advance the project watermark atomically.
create or replace function public.advance_project_lamport(p_id uuid, p_lamport bigint)
returns void language sql security definer as $$
  update public.projects set lamport = greatest(lamport, p_lamport) where id = p_id;
$$;

create or replace function public.increment_track_count(p_user uuid)
returns void language sql security definer as $$
  update public.profiles set track_count = track_count + 1 where id = p_user;
$$;

-- Worker wake-up (production workers LISTEN on this channel).
create or replace function public.notify_analysis_worker(p_path text)
returns void language plpgsql as $$
begin
  perform pg_notify('analysis_jobs', p_path);
end $$;
