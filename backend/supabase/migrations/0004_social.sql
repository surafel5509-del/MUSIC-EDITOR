-- Social graph + feed.
create table public.follows (
  follower_id uuid not null references auth.users(id) on delete cascade,
  followee_id uuid not null references auth.users(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);

create table public.posts (
  id            uuid primary key default gen_random_uuid(),
  author_id     uuid not null references auth.users(id) on delete cascade,
  kind          text not null default 'TRACK' check (kind in ('TRACK','REMIX','WIP','PODCAST_EPISODE','TEXT','CHALLENGE_ENTRY','LIVE_STREAM')),
  text          text check (text is null or char_length(text) <= 2000),
  media_path    text,          -- storage key in the public media bucket
  waveform_path text,          -- pre-rendered waveform PNG/JSON
  duration_frames bigint not null default 0,
  project_id    uuid references public.projects(id) on delete set null,
  remix_of      uuid references public.posts(id) on delete set null,
  challenge_id  uuid,
  genre         text,
  bpm           double precision,
  key_name      text,
  visibility    text not null default 'PUBLIC' check (visibility in ('PUBLIC','FOLLOWERS','LINK_ONLY','PRIVATE')),
  is_premium_only boolean not null default false,
  price_micros  bigint not null default 0,
  like_count    int not null default 0,
  comment_count int not null default 0,
  repost_count  int not null default 0,
  play_count    int not null default 0,
  created_at    timestamptz not null default now(),
  is_deleted    boolean not null default false
);

-- Keyset pagination index for the feed.
create index posts_feed on public.posts (visibility, created_at desc, id desc)
  where is_deleted = false;
create index posts_author on public.posts (author_id, created_at desc);
create index posts_text_trgm on public.posts using gin (text gin_trgm_ops);

create table public.post_likes (
  post_id   uuid not null references public.posts(id) on delete cascade,
  user_id   uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table public.post_comments (
  id         uuid primary key default gen_random_uuid(),
  post_id    uuid not null references public.posts(id) on delete cascade,
  author_id  uuid not null references auth.users(id) on delete cascade,
  parent_id  uuid references public.post_comments(id) on delete cascade,
  text       text not null check (char_length(text) between 1 and 1000),
  like_count int not null default 0,
  created_at timestamptz not null default now(),
  is_deleted boolean not null default false
);

create index post_comments_post on public.post_comments (post_id, created_at);

create table public.challenges (
  id          uuid primary key default gen_random_uuid(),
  title       text not null,
  description text not null,
  starts_at   timestamptz not null,
  ends_at     timestamptz not null,
  rules       jsonb not null default '[]',
  prizes      jsonb not null default '[]',
  cover_path  text,
  required_stem_item uuid,
  entry_count int not null default 0,
  created_at  timestamptz not null default now()
);

create table public.direct_messages (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null,
  sender_id       uuid not null references auth.users(id) on delete cascade,
  text            text check (text is null or char_length(text) <= 2000),
  attachment_path text,
  read_at         timestamptz,
  created_at      timestamptz not null default now()
);

create index dm_conversation on public.direct_messages (conversation_id, created_at desc);

create table public.conversation_members (
  conversation_id uuid not null,
  user_id         uuid not null references auth.users(id) on delete cascade,
  joined_at       timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

-- Denormalized counters via triggers (avoid count(*) on hot paths).
create or replace function public.bump_post_like() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    update public.posts set like_count = like_count + 1 where id = new.post_id;
    return new;
  else
    update public.posts set like_count = greatest(like_count - 1, 0) where id = old.post_id;
    return old;
  end if;
end $$;

create trigger post_likes_bump after insert or delete on public.post_likes
  for each row execute function public.bump_post_like();

create or replace function public.bump_post_comment() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    update public.posts set comment_count = comment_count + 1 where id = new.post_id;
    return new;
  else
    update public.posts set comment_count = greatest(comment_count - 1, 0) where id = old.post_id;
    return old;
  end if;
end $$;

create trigger post_comments_bump after insert or delete on public.post_comments
  for each row execute function public.bump_post_comment();

create or replace function public.bump_follow_counts() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    update public.profiles set follower_count = follower_count + 1 where id = new.followee_id;
    update public.profiles set following_count = following_count + 1 where id = new.follower_id;
  else
    update public.profiles set follower_count = greatest(follower_count - 1, 0) where id = old.followee_id;
    update public.profiles set following_count = greatest(following_count - 1, 0) where id = old.follower_id;
  end if;
  return null;
end $$;

create trigger follows_bump after insert or delete on public.follows
  for each row execute function public.bump_follow_counts();
