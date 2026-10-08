-- Monthly partitioning for the ops log (hot/cold split).
-- pg_cron job creates next month's partition and detaches partitions older
-- than the retention window into cold storage (S3 export via pg_partman in
-- production; the function below is the minimal self-contained version).
create or replace function public.ensure_ops_partition(month date)
returns void language plpgsql as $$
declare
  part_name text := 'ops_' || to_char(month, 'YYYY_MM');
  start_date date := date_trunc('month', month);
  end_date date := start_date + interval '1 month';
begin
  if not exists (select 1 from pg_class where relname = part_name) then
    execute format(
      'create table public.%I partition of public.ops for values from (%L) to (%L)',
      part_name, start_date, end_date);
  end if;
end $$;

-- Create current + next month now; schedule via pg_cron in production:
--   select cron.schedule('ops-partitions', '0 3 25 * *',
--     $$select public.ensure_ops_partition(now() + interval '1 month')$$);
select public.ensure_ops_partition(now());
select public.ensure_ops_partition(now() + interval '1 month');

-- GDPR: hard-delete helper called by delete-account (edge fn) and the
-- nightly erasure job for accounts deleted > 30 days ago.
create or replace function public.purge_user_data(p_user uuid)
returns void language plpgsql security definer as $$
begin
  delete from public.purchases where user_id = p_user;
  delete from public.tips where from_user = p_user or to_user = p_user;
  delete from public.posts where author_id = p_user;
  delete from public.comments where author_id = p_user;
  delete from public.user_samples where owner_id = p_user;
  delete from public.projects where owner_id = p_user;
  delete from public.profiles where id = p_user;
  -- auth.users deletion cascades the remainder.
end $$;
