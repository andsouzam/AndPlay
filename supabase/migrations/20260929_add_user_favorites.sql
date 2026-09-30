create table if not exists public.user_favorites (
  user_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
  content_type text not null check (content_type in ('movie','series')),
  content_id text not null,
  title text not null default '',
  poster text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, content_type, content_id)
);

alter table public.user_favorites enable row level security;
revoke all on public.user_favorites from anon;
grant select, insert, update, delete on public.user_favorites to authenticated;

drop policy if exists "own favorites select" on public.user_favorites;
drop policy if exists "own favorites insert" on public.user_favorites;
drop policy if exists "own favorites update" on public.user_favorites;
drop policy if exists "own favorites delete" on public.user_favorites;

create policy "own favorites select"
on public.user_favorites for select to authenticated
using (auth.uid() = user_id);

create policy "own favorites insert"
on public.user_favorites for insert to authenticated
with check (auth.uid() = user_id);

create policy "own favorites update"
on public.user_favorites for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "own favorites delete"
on public.user_favorites for delete to authenticated
using (auth.uid() = user_id);

drop trigger if exists user_favorites_touch_updated_at on public.user_favorites;
create trigger user_favorites_touch_updated_at
before update on public.user_favorites
for each row execute function public.touch_updated_at();
