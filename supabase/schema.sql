-- Supabase SQL Editor에서 명시적으로 실행하세요. 앱이 DB를 자동 변경하지 않습니다.
create table if not exists public.assistant_history (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    assistant text not null check (assistant in ('policy', 'marketing', 'customer', 'sales')),
    title text not null,
    format text not null,
    profile jsonb not null,
    result jsonb not null,
    created_at timestamptz not null default now()
);

create index if not exists assistant_history_user_date
    on public.assistant_history(user_id, created_at desc);

alter table public.assistant_history enable row level security;
revoke all on public.assistant_history from anon;
grant select, insert on public.assistant_history to authenticated;

drop policy if exists "Users read own assistant history" on public.assistant_history;
create policy "Users read own assistant history"
    on public.assistant_history for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert own assistant history" on public.assistant_history;
create policy "Users insert own assistant history"
    on public.assistant_history for insert to authenticated
    with check ((select auth.uid()) = user_id);
