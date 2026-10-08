-- ─────────────────────────────────────────────────────────────────────────────
-- Row Level Security. Every table is RLS-locked; policies encode the product
-- rules (docs/SECURITY.md). The JWT `sub` claim is the identity; service_role
-- (edge functions) bypasses RLS and must re-check ownership explicitly.
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.profiles enable row level security;
alter table public.projects enable row level security;
alter table public.ops enable row level security;
alter table public.project_members enable row level security;
alter table public.share_links enable row level security;
alter table public.comments enable row level security;
alter table public.project_versions enable row level security;
alter table public.follows enable row level security;
alter table public.posts enable row level security;
alter table public.post_likes enable row level security;
alter table public.post_comments enable row level security;
alter table public.challenges enable row level security;
alter table public.direct_messages enable row level security;
alter table public.conversation_members enable row level security;
alter table public.library_items enable row level security;
alter table public.sample_packs enable row level security;
alter table public.user_samples enable row level security;
alter table public.presets enable row level security;
alter table public.entitlements enable row level security;
alter table public.purchases enable row level security;
alter table public.owned_packs enable row level security;
alter table public.tips enable row level security;

-- Helper: is the caller a member of a project with at least the given role?
create or replace function public.has_project_role(p_project uuid, min_role text)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.projects pr
    left join public.project_members pm on pm.project_id = pr.id
    where pr.id = p_project
      and (
        pr.owner_id = auth.uid()
        or (pm.user_id = auth.uid() and case pm.role
              when 'OWNER' then 4 when 'EDITOR' then 3
              when 'COMMENTER' then 2 when 'VIEWER' then 1 else 0 end
            >= case min_role
              when 'OWNER' then 4 when 'EDITOR' then 3
              when 'COMMENTER' then 2 when 'VIEWER' then 1 else 0 end)
      )
  );
$$;

-- ── Profiles ────────────────────────────────────────────────────────────────
create policy profiles_select_all on public.profiles for select using (true);
create policy profiles_update_self on public.profiles for update using (auth.uid() = id);
create policy profiles_insert_self on public.profiles for insert with check (auth.uid() = id);

-- ── Projects ────────────────────────────────────────────────────────────────
create policy projects_select_member on public.projects for select
  using (is_deleted = false and (owner_id = auth.uid() or public.has_project_role(id, 'VIEWER')));
create policy projects_insert_own on public.projects for insert
  with check (owner_id = auth.uid());
create policy projects_update_member on public.projects for update
  using (owner_id = auth.uid() or public.has_project_role(id, 'EDITOR'));
create policy projects_delete_owner on public.projects for delete
  using (owner_id = auth.uid());

-- ── Ops (CRDT log) ──────────────────────────────────────────────────────────
create policy ops_select_member on public.ops for select
  using (public.has_project_role(project_id, 'VIEWER'));
create policy ops_insert_member on public.ops for insert
  with check (public.has_project_role(project_id, 'EDITOR') and author_id = auth.uid());
-- Ops are immutable: no update/delete policies (auditability of the CRDT log).

-- ── Members & shares ────────────────────────────────────────────────────────
create policy members_select on public.project_members for select
  using (public.has_project_role(project_id, 'VIEWER'));
create policy members_manage_owner on public.project_members for all
  using (exists (select 1 from public.projects p where p.id = project_id and p.owner_id = auth.uid()));

create policy shares_select_owner on public.share_links for select
  using (created_by = auth.uid());
create policy shares_insert_owner on public.share_links for insert
  with check (created_by = auth.uid() and exists (
    select 1 from public.projects p where p.id = project_id and p.owner_id = auth.uid()));
create policy shares_revoke_owner on public.share_links for delete
  using (created_by = auth.uid());

-- ── Comments ────────────────────────────────────────────────────────────────
create policy comments_select on public.comments for select
  using (public.has_project_role(project_id, 'VIEWER'));
create policy comments_insert on public.comments for insert
  with check (public.has_project_role(project_id, 'COMMENTER') and author_id = auth.uid());
create policy comments_resolve on public.comments for update
  using (author_id = auth.uid() or public.has_project_role(project_id, 'EDITOR'));
create policy comments_delete_own on public.comments for delete
  using (author_id = auth.uid());

-- ── Versions ────────────────────────────────────────────────────────────────
create policy versions_select on public.project_versions for select
  using (public.has_project_role(project_id, 'VIEWER'));
create policy versions_insert on public.project_versions for insert
  with check (public.has_project_role(project_id, 'EDITOR'));

-- ── Social ──────────────────────────────────────────────────────────────────
create policy follows_select on public.follows for select using (true);
create policy follows_manage on public.follows for all using (follower_id = auth.uid());

create policy posts_select_public on public.posts for select
  using (is_deleted = false and (
    visibility = 'PUBLIC'
    or author_id = auth.uid()
    or (visibility = 'FOLLOWERS' and exists (
         select 1 from public.follows f where f.follower_id = auth.uid() and f.followee_id = posts.author_id))
  ));
create policy posts_insert_own on public.posts for insert with check (author_id = auth.uid());
create policy posts_update_own on public.posts for update using (author_id = auth.uid());
create policy posts_delete_own on public.posts for delete using (author_id = auth.uid());

create policy likes_select on public.post_likes for select using (true);
create policy likes_manage on public.post_likes for all using (user_id = auth.uid());

create policy post_comments_select on public.post_comments for select using (not is_deleted);
create policy post_comments_insert on public.post_comments for insert with check (author_id = auth.uid());
create policy post_comments_delete on public.post_comments for delete using (author_id = auth.uid());

create policy challenges_select on public.challenges for select using (true);

create policy dm_select_member on public.direct_messages for select
  using (exists (select 1 from public.conversation_members cm
                 where cm.conversation_id = direct_messages.conversation_id and cm.user_id = auth.uid()));
create policy dm_insert_member on public.direct_messages for insert
  with check (sender_id = auth.uid() and exists (select 1 from public.conversation_members cm
                 where cm.conversation_id = direct_messages.conversation_id and cm.user_id = auth.uid()));
create policy dm_members_select on public.conversation_members for select using (user_id = auth.uid());
create policy dm_members_insert on public.conversation_members for insert with check (user_id = auth.uid());

-- ── Library (public read; writes via service role only) ─────────────────────
create policy library_read on public.library_items for select using (true);
create policy packs_read on public.sample_packs for select using (true);
create policy presets_read on public.presets for select using (true);
create policy presets_insert_user on public.presets for insert
  with check (author_id = auth.uid() and is_factory = false);
create policy presets_update_own on public.presets for update using (author_id = auth.uid());
create policy presets_delete_own on public.presets for delete using (author_id = auth.uid());

create policy samples_own on public.user_samples for all using (owner_id = auth.uid());

-- ── Monetization (read own; writes via verified edge functions) ─────────────
create policy entitlements_own on public.entitlements for select using (user_id = auth.uid());
create policy purchases_own on public.purchases for select using (user_id = auth.uid());
create policy owned_packs_own on public.owned_packs for select using (user_id = auth.uid());
create policy tips_to_own on public.tips for select using (to_user = auth.uid() or from_user = auth.uid());
create policy tips_insert on public.tips for insert with check (from_user = auth.uid());

-- Storage bucket policies (projects/exports private, media/packs public-read).
create policy storage_projects_member on storage.objects for select
  using (bucket_id = 'projects' and public.has_project_role((storage.foldername(name))[1]::uuid, 'VIEWER'));
create policy storage_projects_editor on storage.objects for insert
  with check (bucket_id = 'projects' and public.has_project_role((storage.foldername(name))[1]::uuid, 'EDITOR'));
create policy storage_media_public on storage.objects for select
  using (bucket_id in ('media', 'packs'));
create policy storage_exports_own on storage.objects for select
  using (bucket_id = 'exports' and (storage.foldername(name))[1] = auth.uid()::text);
