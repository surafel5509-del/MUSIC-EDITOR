-- Public profile, auto-created from auth.users via trigger.
create table public.profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  handle        text unique,
  display_name  text not null default 'Musician',
  bio           text default '',
  avatar_url    text,
  banner_url    text,
  genres        text[] default '{}',
  instruments   text[] default '{}',
  links         jsonb default '[]',
  location      text,
  follower_count int not null default 0,
  following_count int not null default 0,
  track_count   int not null default 0,
  is_verified_artist boolean not null default false,
  tips_enabled  boolean not null default false,
  tip_jar_address text,
  gdpr_consents jsonb not null default '{}',   -- {"analytics":bool,"ads":bool,"crash":bool}
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create index profiles_handle_trgm on public.profiles using gin (handle gin_trgm_ops);

-- Handle validation: 3-24 chars, lowercase alnum + underscore.
alter table public.profiles add constraint handle_format
  check (handle is null or handle ~ '^[a-z0-9_]{3,24}$');

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, handle, display_name, avatar_url)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'handle', 'user_' || substr(new.id::text, 1, 8)),
    coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email, '@', 1), 'Musician'),
    new.raw_user_meta_data->>'avatar_url'
  );
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- updated_at maintenance.
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;

create trigger profiles_touch_updated before update on public.profiles
  for each row execute function public.touch_updated_at();
