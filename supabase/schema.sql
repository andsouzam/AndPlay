-- EPlay Web / Supabase Free
-- Não armazena vídeos nem catálogo. Apenas dados pessoais da conta.

create table if not exists public.user_preferences (
  user_id uuid primary key references auth.users(id) on delete cascade default auth.uid(),
  preferences jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.watch_history (
  user_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
  content_type text not null check (content_type in ('movie','series')),
  content_id text not null,
  sort_order integer not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, content_type, content_id)
);

alter table if exists public.watch_history add column if not exists sort_order integer not null default 0;

create table if not exists public.watch_progress (
  user_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
  content_type text not null check (content_type in ('movie','series')),
  content_id text not null,
  position double precision not null default 0,
  duration double precision not null default 0,
  title text not null default '',
  poster text not null default '',
  series_id text,
  season_num integer,
  episode_num integer,
  updated_at timestamptz not null default now(),
  primary key (user_id, content_type, content_id)
);

alter table public.user_preferences enable row level security;
alter table public.watch_history enable row level security;
alter table public.watch_progress enable row level security;

revoke all on public.user_preferences from anon;
revoke all on public.watch_history from anon;
revoke all on public.watch_progress from anon;

grant select, insert, update, delete on public.user_preferences to authenticated;
grant select, insert, update, delete on public.watch_history to authenticated;
grant select, insert, update, delete on public.watch_progress to authenticated;

create policy "own preferences select"
on public.user_preferences for select to authenticated
using (auth.uid() = user_id);

create policy "own preferences insert"
on public.user_preferences for insert to authenticated
with check (auth.uid() = user_id);

create policy "own preferences update"
on public.user_preferences for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "own preferences delete"
on public.user_preferences for delete to authenticated
using (auth.uid() = user_id);

create policy "own history select"
on public.watch_history for select to authenticated
using (auth.uid() = user_id);

create policy "own history insert"
on public.watch_history for insert to authenticated
with check (auth.uid() = user_id);

create policy "own history update"
on public.watch_history for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "own history delete"
on public.watch_history for delete to authenticated
using (auth.uid() = user_id);

create policy "own progress select"
on public.watch_progress for select to authenticated
using (auth.uid() = user_id);

create policy "own progress insert"
on public.watch_progress for insert to authenticated
with check (auth.uid() = user_id);

create policy "own progress update"
on public.watch_progress for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create policy "own progress delete"
on public.watch_progress for delete to authenticated
using (auth.uid() = user_id);

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists user_preferences_touch_updated_at on public.user_preferences;
create trigger user_preferences_touch_updated_at
before update on public.user_preferences
for each row execute function public.touch_updated_at();

drop trigger if exists watch_history_touch_updated_at on public.watch_history;
create trigger watch_history_touch_updated_at
before update on public.watch_history
for each row execute function public.touch_updated_at();

drop trigger if exists watch_progress_touch_updated_at on public.watch_progress;
create trigger watch_progress_touch_updated_at
before update on public.watch_progress
for each row execute function public.touch_updated_at();
