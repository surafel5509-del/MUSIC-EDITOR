-- Core extensions.
create extension if not exists "pgcrypto";      -- gen_random_uuid
create extension if not exists "pg_trgm";       -- fuzzy search for library/feed
create extension if not exists "uuid-ossp";
