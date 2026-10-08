-- Realtime: broadcast op inserts on the per-project channel.
-- Clients subscribe to `realtime:project:<projectId>`; the push function also
-- broadcasts explicitly, this publication is the safety net.
alter publication supabase_realtime add table public.ops;
alter publication supabase_realtime add table public.comments;
alter publication supabase_realtime add table public.post_comments;
alter publication supabase_realtime add table public.direct_messages;
