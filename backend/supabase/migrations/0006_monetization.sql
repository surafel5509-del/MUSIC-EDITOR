-- Entitlements, purchases, tips.
create table public.entitlements (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  tier        text not null default 'FREE' check (tier in ('FREE','PRO','PRO_PLUS')),
  expires_at  timestamptz,                 -- null = lifetime
  in_grace    boolean not null default false,
  storage_used_bytes bigint not null default 0,
  updated_at  timestamptz not null default now()
);

create table public.purchases (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  product_id    text not null,
  purchase_token text not null,
  package_name  text not null,
  kind          text not null check (kind in ('SUB_MONTHLY','SUB_ANNUAL','SOUND_PACK','PRESET_PACK','ARTIST_SERVICE','TIP')),
  state         text not null default 'PURCHASED' check (state in ('PENDING','PURCHASED','FAILED','CANCELLED','REFUNDED')),
  price_micros  bigint not null default 0,
  currency      text not null default 'USD',
  verified_at   timestamptz,
  created_at    timestamptz not null default now(),
  unique (purchase_token)
);

create index purchases_user on public.purchases (user_id, created_at desc);

create table public.owned_packs (
  user_id   uuid not null references auth.users(id) on delete cascade,
  pack_id   uuid not null references public.sample_packs(id) on delete cascade,
  granted_at timestamptz not null default now(),
  primary key (user_id, pack_id)
);

create table public.tips (
  id          uuid primary key default gen_random_uuid(),
  from_user   uuid references auth.users(id) on delete set null,
  to_user     uuid not null references auth.users(id) on delete cascade,
  post_id     uuid references public.posts(id) on delete set null,
  amount_micros bigint not null check (amount_micros > 0),
  currency    text not null default 'USD',
  message     text check (message is null or char_length(message) <= 500),
  created_at  timestamptz not null default now()
);

create index tips_to_user on public.tips (to_user, created_at desc);

-- Follows -> feeds: helper view for the "Following" tab.
create or replace view public.feed_following as
select p.* from public.posts p
join public.follows f on f.followee_id = p.author_id
where p.visibility = 'PUBLIC' and p.is_deleted = false;
