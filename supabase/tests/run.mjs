// Applies every migration to an in-memory Postgres (PGlite) and runs behavioural checks
// for matchmaking, calls, rooms, messaging, wallet and RLS. Uses throwaway in-memory data only;
// nothing here touches a real Supabase project.
//
//   npm --prefix supabase/tests ci && node supabase/tests/run.mjs
import { PGlite } from "@electric-sql/pglite";
import { pgcrypto } from "@electric-sql/pglite/contrib/pgcrypto";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, "..", "migrations");

const db = new PGlite({ extensions: { pgcrypto } });

async function exec(sql) {
  await db.exec(sql);
}

async function applyMigrations() {
  await exec(readFileSync(join(here, "supabase_stubs.sql"), "utf8"));
  for (const file of readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort()) {
    try {
      await exec(readFileSync(join(migrationsDir, file), "utf8"));
    } catch (e) {
      throw new Error(`Migration ${file} failed: ${e.message}${e.position ? ` (position ${e.position})` : ""}${e.where ? `\n${e.where}` : ""}`);
    }
    console.log(`applied ${file}`);
  }
}

// Runs a statement as an authenticated user, the way PostgREST does (SET ROLE + JWT claim).
async function as(uid, sql, params = []) {
  await db.exec("reset role");
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [uid ?? ""]);
  await db.exec(uid ? "set role authenticated" : "set role anon");
  try {
    const res = await db.query(sql, params);
    return res.rows;
  } finally {
    await db.exec("reset role");
  }
}

async function asAdminSql(sql, params = []) {
  await db.exec("reset role");
  await db.query("select set_config('request.jwt.claim.sub', '', false)");
  return (await db.query(sql, params)).rows;
}

async function rpc(uid, fn, args = {}) {
  const names = Object.keys(args);
  const sql = `select public.${fn}(${names.map((n, i) => `${n} => $${i + 1}`).join(", ")}) as r`;
  const rows = await as(uid, sql, Object.values(args));
  return rows[0].r;
}

async function expectError(promise, code) {
  try {
    await promise;
  } catch (e) {
    if (code && !String(e.message).includes(code)) {
      throw new Error(`expected error '${code}' but got: ${e.message}`);
    }
    return e;
  }
  throw new Error(`expected error '${code}' but the call succeeded`);
}

let passed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ok  ${name}`);
  } catch (e) {
    console.error(`  FAIL ${name}\n       ${e.stack ?? e.message}`);
    process.exitCode = 1;
  }
}

async function newUser(username, languages = ["tr"], interests = ["music", "games"]) {
  const id = randomUUID();
  await asAdminSql("insert into auth.users (id, email) values ($1, $2)", [id, `${username}@test.invalid`]);
  await rpc(id, "complete_onboarding", {
    p_username: username,
    p_display_name: username,
    p_birth_date: "1995-05-05",
    p_interests: interests,
    p_languages: languages,
    p_terms_version: "2026-09",
    p_privacy_version: "2026-09",
    p_community_version: "2026-09",
  });
  return id;
}

await applyMigrations();
console.log("\nbehaviour");

const alice = await newUser("alice");
const bob = await newUser("bob");
const carol = await newUser("carol", ["en"], ["books"]);

await test("underage onboarding is rejected", async () => {
  const id = randomUUID();
  await asAdminSql("insert into auth.users (id) values ($1)", [id]);
  await expectError(rpc(id, "complete_onboarding", {
    p_username: "kid_user", p_display_name: "Kid", p_birth_date: new Date(Date.now() - 16 * 365 * 864e5).toISOString().slice(0, 10),
    p_interests: [], p_languages: ["tr"], p_terms_version: "1", p_privacy_version: "1", p_community_version: "1",
  }), "underage");
});

await test("usernames are unique case-insensitively", async () => {
  const id = randomUUID();
  await asAdminSql("insert into auth.users (id) values ($1)", [id]);
  await expectError(rpc(id, "complete_onboarding", {
    p_username: "ALICE", p_display_name: "Other", p_birth_date: "1990-01-01", p_interests: [], p_languages: ["tr"],
    p_terms_version: "1", p_privacy_version: "1", p_community_version: "1",
  }), "username_taken");
});

await test("birth date is private", async () => {
  const rows = await as(bob, "select * from public.user_private where user_id = $1", [alice]);
  assert.equal(rows.length, 0);
  const own = await as(alice, "select birth_date from public.user_private where user_id = $1", [alice]);
  assert.equal(own.length, 1);
});

await test("clients cannot grant themselves staff, coins or premium", async () => {
  await expectError(as(alice, "insert into public.admin_roles (user_id, role) values ($1, 'admin')", [alice]), "permission denied");
  await expectError(as(alice, "update public.wallet_accounts set balance = 1000000 where user_id = $1", [alice]), "permission denied");
  await expectError(as(alice, "insert into public.subscription_entitlements (user_id, product_id, status) values ($1, 'x', 'active')", [alice]), "permission denied");
  await expectError(as(alice, "select public.record_verified_purchase($1, 'coins_100', 'consumable', 'h', null, 'purchased', now(), null, false, null)", [alice]), "permission denied");
  await expectError(as(alice, "update public.profiles set gifts_received = 999 where id = $1", [alice]), "permission denied");
});

let session;
await test("two queued users are matched atomically and anonymously", async () => {
  const a = await rpc(alice, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Fox", p_languages: ["tr"], p_strict_language: false });
  assert.equal(a.state, "waiting");
  const b = await rpc(bob, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Owl", p_languages: ["tr"], p_strict_language: false });
  assert.equal(b.state, "matched");
  assert.equal(b.peer_alias, "Fox");
  assert.deepEqual([...b.common_interests].sort(), ["games", "music"]);
  assert.equal(JSON.stringify(b).includes(alice), false, "peer id must not leak");
  await expectError(as(bob, "select * from public.matches"), "permission denied");
  const stateA = await rpc(alice, "queue_heartbeat");
  assert.equal(stateA.state, "matched");
  await rpc(alice, "respond_match", { p_match: b.match_id, p_accept: true });
  const accepted = await rpc(bob, "respond_match", { p_match: b.match_id, p_accept: true });
  assert.equal(accepted.state, "in_session");
  session = accepted.session_id;
});

await test("a third user cannot see or join the session", async () => {
  const rows = await as(carol, "select * from public.call_sessions where id = $1", [session]);
  assert.equal(rows.length, 0);
  await expectError(rpc(carol, "get_call_state", { p_session: session }), "not_found");
  await expectError(rpc(carol, "mark_rtc_joined", { p_session: session }), "not_found");
});

await test("the timer starts only after both sides joined RTC", async () => {
  const s1 = await rpc(alice, "mark_rtc_joined", { p_session: session });
  assert.equal(s1.status, "connecting");
  assert.equal(s1.ends_at, null);
  const s2 = await rpc(bob, "mark_rtc_joined", { p_session: session });
  assert.equal(s2.status, "active");
  assert.ok(s2.ends_at);
  assert.equal(s2.peer_profile, null, "random calls stay anonymous");
});

await test("an extension needs both sides and is applied once", async () => {
  const before = await rpc(alice, "get_call_state", { p_session: session });
  const r1 = await rpc(alice, "request_extension", { p_session: session });
  assert.equal(r1.extension_requested_by_me, true);
  assert.equal(r1.ends_at, before.ends_at);
  await rpc(alice, "request_extension", { p_session: session });
  const r2 = await rpc(bob, "request_extension", { p_session: session });
  assert.ok(new Date(r2.ends_at) > new Date(before.ends_at));
  assert.equal(r2.extensions_applied, 1);
});

await test("Agora access is authorized per session on the server", async () => {
  const a = await rpc(alice, "rtc_authorize", { p_kind: "call", p_id: session });
  assert.ok(a.channel_name.startsWith("st_"));
  assert.equal(a.role, "publisher");
  const b = await rpc(bob, "rtc_authorize", { p_kind: "call", p_id: session });
  assert.equal(a.channel_name, b.channel_name);
  assert.notEqual(a.uid, b.uid);
  await expectError(rpc(carol, "rtc_authorize", { p_kind: "call", p_id: session }), "not_found");
  await expectError(rpc(carol, "rtc_authorize", { p_kind: "room", p_id: randomUUID() }), "not_room_member");
});

await test("a user cannot queue while in a call", async () => {
  await expectError(rpc(alice, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Fox", p_languages: ["tr"], p_strict_language: false }), "already_in_call");
});

await test("mutual 'talk again' connects as friends, one-sided does not reveal anything", async () => {
  await rpc(alice, "end_call", { p_session: session, p_reason: "hangup" });
  const f1 = await rpc(alice, "submit_call_feedback", { p_session: session, p_wants_again: true });
  assert.equal(f1.mutual, false);
  assert.equal(f1.peer_id, null);
  const f2 = await rpc(bob, "submit_call_feedback", { p_session: session, p_wants_again: true });
  assert.equal(f2.mutual, true);
  const friends = await rpc(alice, "list_friends");
  assert.equal(friends.length, 1);
  assert.equal(friends[0].id, bob);
});

await test("strict language filter is respected", async () => {
  await rpc(alice, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Fox", p_languages: ["tr"], p_strict_language: true });
  const c = await rpc(carol, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Cat", p_languages: ["en"], p_strict_language: false });
  assert.equal(c.state, "waiting");
  await rpc(alice, "leave_queue");
  await rpc(carol, "leave_queue");
});

await test("blocked users are never matched", async () => {
  const dave = await newUser("dave");
  const erin = await newUser("erin");
  await rpc(dave, "block_user", { p_user: erin });
  await rpc(dave, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Dave", p_languages: ["tr"], p_strict_language: false });
  const e = await rpc(erin, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Erin", p_languages: ["tr"], p_strict_language: false });
  assert.equal(e.state, "waiting");
  await rpc(dave, "leave_queue");
  await rpc(erin, "leave_queue");
  await expectError(rpc(erin, "get_profile", { p_user: dave }), "not_found");
});

await test("video matching stays off until the flag is enabled", async () => {
  await expectError(rpc(carol, "join_queue", { p_mode: "video", p_intent: "chat", p_alias: "Cat", p_languages: ["en"], p_strict_language: false }), "feature_disabled");
});

await test("declining a match requeues the other person", async () => {
  const x = await newUser("xavier");
  const y = await newUser("yara");
  await rpc(x, "join_queue", { p_mode: "text", p_intent: "chat", p_alias: "Xa", p_languages: ["tr"], p_strict_language: false });
  const m = await rpc(y, "join_queue", { p_mode: "text", p_intent: "chat", p_alias: "Ya", p_languages: ["tr"], p_strict_language: false });
  assert.equal(m.state, "matched");
  await rpc(y, "respond_match", { p_match: m.match_id, p_accept: false });
  const sx = await rpc(x, "queue_heartbeat");
  assert.equal(sx.state, "waiting");
  const sy = await rpc(y, "queue_heartbeat");
  assert.equal(sy.state, "idle");
  await rpc(x, "leave_queue");
});

await test("text introductions keep sender identity as slots only", async () => {
  const p = await newUser("pia");
  const q = await newUser("quinn");
  await rpc(p, "join_queue", { p_mode: "text", p_intent: "chat", p_alias: "Pi", p_languages: ["tr"], p_strict_language: false });
  const m = await rpc(q, "join_queue", { p_mode: "text", p_intent: "chat", p_alias: "Qu", p_languages: ["tr"], p_strict_language: false });
  await rpc(p, "respond_match", { p_match: m.match_id, p_accept: true });
  const s = await rpc(q, "respond_match", { p_match: m.match_id, p_accept: true });
  const st = await rpc(q, "get_call_state", { p_session: s.session_id });
  assert.equal(st.status, "active");
  const msgId = randomUUID();
  await rpc(q, "send_match_message", { p_session: s.session_id, p_id: msgId, p_body: "Merhaba!" });
  await rpc(q, "send_match_message", { p_session: s.session_id, p_id: msgId, p_body: "Merhaba!" });
  const list = await rpc(p, "list_match_messages", { p_session: s.session_id });
  assert.equal(list.length, 1, "idempotent insert");
  assert.equal(list[0].mine, false);
  const raw = await as(p, "select * from public.match_messages");
  assert.equal(Object.keys(raw[0]).includes("sender_id"), false);
  // Upgrade to voice needs both.
  const u1 = await rpc(p, "request_mode_change", { p_session: s.session_id, p_mode: "voice" });
  assert.equal(u1.mode, "text");
  const u2 = await rpc(q, "request_mode_change", { p_session: s.session_id, p_mode: "voice" });
  assert.equal(u2.mode, "voice");
  // Reporting the anonymous peer works without revealing them.
  const reportId = await rpc(p, "create_report", { p_target_type: "call", p_target_id: s.session_id, p_reason: "harassment", p_details: "test" });
  assert.ok(reportId);
  const reports = await rpc(p, "my_reports");
  assert.equal(JSON.stringify(reports).includes(q), false);
  await expectError(as(p, "select * from public.reports"), "permission denied");
  await rpc(p, "end_call", { p_session: s.session_id });
});

let room;
await test("password rooms hash the password and reject wrong attempts", async () => {
  const r = await rpc(alice, "create_room", {
    p_title: "Gece sohbeti", p_description: "", p_topic: "music", p_tags: ["müzik"], p_language: "tr", p_visibility: "password",
    p_password: "gizli123", p_max_participants: 3, p_max_speakers: 2, p_hand_raise_required: true, p_text_chat_enabled: true,
  });
  room = r.id;
  assert.equal(r.my_role, "owner");
  assert.equal(JSON.stringify(r).includes("gizli123"), false);
  await expectError(as(alice, "select * from app_private.room_secrets"), "permission denied");
  await expectError(rpc(bob, "join_room", { p_room: room, p_password: "yanlis" }), "wrong_room_password");
  const joined = await rpc(bob, "join_room", { p_room: room, p_password: "gizli123" });
  assert.equal(joined.my_role, "listener");
});

await test("listeners cannot unmute; speaking requires an accepted invite", async () => {
  await expectError(rpc(bob, "set_self_muted", { p_room: room, p_muted: false }), "cannot_unmute");
  await rpc(bob, "raise_hand", { p_room: room, p_raised: true });
  const ownerView = await rpc(alice, "get_room", { p_room: room });
  assert.equal(ownerView.pending_requests.length, 1);
  await rpc(alice, "invite_to_speak", { p_room: room, p_user: bob });
  const after = await rpc(bob, "get_room", { p_room: room });
  assert.equal(after.my_role, "speaker");
  const me = after.members.find((m) => m.user_id === bob);
  assert.equal(me.self_muted, true, "promotion never opens the mic");
  await rpc(bob, "set_self_muted", { p_room: room, p_muted: false });
  await rpc(alice, "moderator_mute", { p_room: room, p_user: bob, p_muted: true });
  await expectError(rpc(bob, "set_self_muted", { p_room: room, p_muted: false }), "cannot_unmute");
  await rpc(alice, "moderator_mute", { p_room: room, p_user: bob, p_muted: false });
  const lifted = (await rpc(bob, "get_room", { p_room: room })).members.find((m) => m.user_id === bob);
  assert.equal(lifted.self_muted, true, "lifting a mute keeps the mic closed");
});

await test("room tokens follow the member's role", async () => {
  const owner = await rpc(alice, "rtc_authorize", { p_kind: "room", p_id: room });
  assert.equal(owner.role, "publisher");
  const zoe = await newUser("zoe");
  await rpc(zoe, "join_room", { p_room: room, p_password: "gizli123" });
  const listener = await rpc(zoe, "rtc_authorize", { p_kind: "room", p_id: room });
  assert.equal(listener.role, "subscriber");
  await rpc(zoe, "leave_room", { p_room: room });
  await expectError(rpc(zoe, "rtc_authorize", { p_kind: "room", p_id: room }), "not_room_member");
});

await test("room capacity is enforced and kicked users are banned", async () => {
  await rpc(carol, "join_room", { p_room: room, p_password: "gizli123" });
  const zed = await newUser("zed");
  await expectError(rpc(zed, "join_room", { p_room: room, p_password: "gizli123" }), "room_full");
  await rpc(alice, "kick_from_room", { p_room: room, p_user: carol, p_ban: true, p_reason: "spam" });
  await expectError(rpc(carol, "join_room", { p_room: room, p_password: "gizli123" }), "room_banned");
  await expectError(rpc(bob, "kick_from_room", { p_room: room, p_user: alice, p_ban: false }), "forbidden");
});

await test("owner leaving hands the room to a speaker", async () => {
  await rpc(alice, "leave_room", { p_room: room });
  const st = await rpc(bob, "get_room", { p_room: room });
  assert.equal(st.my_role, "owner");
  assert.equal(st.status, "open");
  await rpc(bob, "leave_room", { p_room: room });
  const closed = await asAdminSql("select status, close_reason from public.rooms where id = $1", [room]);
  assert.equal(closed[0].status, "closed");
});

await test("room list is served through RLS", async () => {
  const pub = await rpc(carol, "create_room", {
    p_title: "Book club", p_description: "", p_topic: "books", p_tags: [], p_language: "en", p_visibility: "public",
    p_password: null, p_max_participants: 20, p_max_speakers: 4, p_hand_raise_required: false, p_text_chat_enabled: true,
  });
  const inv = await rpc(bob, "create_room", {
    p_title: "Private talk", p_description: "", p_topic: null, p_tags: [], p_language: "tr", p_visibility: "invite",
    p_password: null, p_max_participants: 5, p_max_speakers: 2, p_hand_raise_required: true, p_text_chat_enabled: true,
  });
  const list = await rpc(alice, "list_rooms", {});
  const ids = list.map((r) => r.id);
  assert.ok(ids.includes(pub.id));
  assert.ok(!ids.includes(inv.id), "invite-only rooms are hidden");
  await expectError(rpc(alice, "join_room", { p_room: inv.id }), "invite_required");
  await rpc(bob, "create_room_invite", { p_room: inv.id, p_user: alice });
  const j = await rpc(alice, "join_room", { p_room: inv.id });
  assert.equal(j.my_role, "listener");
  await rpc(alice, "leave_room", { p_room: inv.id });
  await rpc(bob, "leave_room", { p_room: inv.id });
  await rpc(carol, "leave_room", { p_room: pub.id });
});

await test("direct calls: friends only, ring, answer, no timer", async () => {
  const call = await rpc(alice, "start_direct_call", { p_user: bob, p_mode: "voice" });
  assert.equal(call.status, "ringing");
  assert.equal(call.peer_profile.id, bob, "friends see each other");
  await expectError(rpc(carol, "start_direct_call", { p_user: alice, p_mode: "voice" }), "call_not_allowed");
  const incoming = await as(bob, "select kind from public.notifications where kind = 'incoming_call'");
  assert.equal(incoming.length, 1);
  const ans = await rpc(bob, "answer_call", { p_session: call.session_id, p_accept: true });
  assert.equal(ans.status, "connecting");
  await rpc(alice, "mark_rtc_joined", { p_session: call.session_id });
  const live = await rpc(bob, "mark_rtc_joined", { p_session: call.session_id });
  assert.equal(live.status, "active");
  assert.equal(live.ends_at, null);
  await rpc(bob, "end_call", { p_session: call.session_id });
});

await test("direct messages: members only, idempotent, blocked users cannot send", async () => {
  const conv = await rpc(alice, "get_or_create_direct", { p_user: bob });
  const id = randomUUID();
  await as(alice, "insert into public.messages (id, conversation_id, sender_id, kind, body) values ($1, $2, $3, 'text', 'selam')", [id, conv, alice]);
  await expectError(as(alice, "insert into public.messages (id, conversation_id, sender_id, kind, body) values ($1, $2, $3, 'text', 'selam')", [id, conv, alice]), "duplicate key");
  const bobSees = await as(bob, "select body from public.messages where conversation_id = $1", [conv]);
  assert.equal(bobSees.length, 1);
  const carolSees = await as(carol, "select body from public.messages where conversation_id = $1", [conv]);
  assert.equal(carolSees.length, 0);
  await expectError(as(carol, "insert into public.messages (id, conversation_id, sender_id, kind, body) values ($1, $2, $3, 'text', 'hi')", [randomUUID(), conv, carol]), "row-level security");
  await expectError(as(alice, "insert into public.messages (id, conversation_id, sender_id, kind, body) values ($1, $2, $3, 'text', 'spoof')", [randomUUID(), conv, bob]), "row-level security");
  await expectError(as(alice, "update public.messages set body = 'x' where id = $1", [id]), "permission denied");
  const list = await rpc(bob, "list_conversations", {});
  assert.equal(list[0].unread_count, 1);
  await rpc(bob, "mark_conversation_read", { p_conversation: conv });
  assert.equal((await rpc(bob, "list_conversations", {}))[0].unread_count, 0);
  await rpc(bob, "block_user", { p_user: alice });
  await expectError(as(alice, "insert into public.messages (id, conversation_id, sender_id, kind, body) values ($1, $2, $3, 'text', 'hey')", [randomUUID(), conv, alice]), "row-level security");
  await rpc(bob, "unblock_user", { p_user: alice });
});

await test("non-friends cannot open a direct chat when the policy is friends-only", async () => {
  await expectError(rpc(carol, "get_or_create_direct", { p_user: alice }), "messaging_not_allowed");
});

await test("gifts: disabled by flag, then atomic and never negative", async () => {
  await expectError(rpc(alice, "send_gift", { p_gift_code: "rose", p_context_type: "profile", p_context_id: carol, p_idempotency_key: "k-00000001" }), "feature_disabled");
  await asAdminSql("update public.feature_flags set enabled = true where key = 'gifts'");
  await expectError(rpc(alice, "send_gift", { p_gift_code: "rose", p_context_type: "profile", p_context_id: carol, p_idempotency_key: "k-00000001" }), "insufficient_balance");
  const tx = await asAdminSql("select count(*)::int as n from public.gift_transactions");
  assert.equal(tx[0].n, 0, "failed gift leaves no record");
  // Simulate the verified-purchase path the Edge Function uses (service role).
  await asAdminSql(`update public.app_settings set value = '{"coins_100": 100}' where key = 'coin_products'`);
  await asAdminSql("set role service_role");
  await db.query("select public.record_verified_purchase($1, 'coins_100', 'consumable', 'hash-1', 'GPA.1', 'purchased', now(), null, false, null)", [alice]);
  await db.query("select public.record_verified_purchase($1, 'coins_100', 'consumable', 'hash-1', 'GPA.1', 'purchased', now(), null, false, null)", [alice]);
  await expectError(db.query("select public.record_verified_purchase($1, 'coins_100', 'consumable', 'hash-1', 'GPA.1', 'purchased', now(), null, false, null)", [bob]), "purchase_owned_by_other_account");
  await db.exec("reset role");
  const ent = await rpc(alice, "my_entitlements");
  assert.equal(Number(ent.balance), 100, "a purchase token credits exactly once");
  const g = await rpc(alice, "send_gift", { p_gift_code: "rose", p_context_type: "profile", p_context_id: carol, p_idempotency_key: "k-00000002" });
  assert.equal(Number(g.balance), 80);
  const again = await rpc(alice, "send_gift", { p_gift_code: "rose", p_context_type: "profile", p_context_id: carol, p_idempotency_key: "k-00000002" });
  assert.equal(Number(again.balance), 80, "idempotent retry does not charge twice");
});

await test("staff API is closed to regular users and audited for staff", async () => {
  await expectError(rpc(alice, "admin_dashboard"), "forbidden");
  await asAdminSql("insert into public.admin_roles (user_id, role) values ($1, 'admin')", [carol]);
  const d = await rpc(carol, "admin_dashboard");
  assert.ok(Number(d.users_total) >= 3);
  const rid = await rpc(carol, "admin_restrict_user", { p_user: bob, p_kind: "matching", p_hours: 24, p_reason: "test restriction" });
  assert.ok(rid);
  await expectError(rpc(bob, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Owl", p_languages: ["tr"], p_strict_language: false }), "account_restricted");
  const logs = await as(carol, "select action from public.audit_logs where action = 'restrict_user'");
  assert.equal(logs.length, 1);
  assert.equal((await as(alice, "select * from public.audit_logs")).length, 0);
  const appeal = await rpc(bob, "create_appeal", { p_restriction: rid, p_body: "Bu kısıtlamanın hatalı olduğunu düşünüyorum." });
  await rpc(carol, "admin_decide_appeal", { p_appeal: appeal, p_overturn: true, p_note: "ok" });
  const st = await rpc(bob, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Owl", p_languages: ["tr"], p_strict_language: false });
  assert.ok(["waiting", "matched"].includes(st.state));
  await rpc(bob, "leave_queue");
});

await test("feed shows only real, visible posts and respects friends-only visibility", async () => {
  const author = await newUser("fatma");
  const friend = await newUser("gokhan");
  const stranger = await newUser("hande");
  assert.equal((await rpc(stranger, "get_feed", { p_tab: "new" })).length, 0, "no fabricated content");
  const req = await rpc(author, "send_friend_request", { p_user: friend });
  await rpc(friend, "respond_friend_request", { p_request: req, p_accept: true });
  const pid = randomUUID();
  const post = { p_id: pid, p_kind: "text", p_body: "İlk paylaşım", p_media: [], p_audio_path: null,
                 p_audio_duration_ms: null, p_interests: ["music"], p_visibility: "friends" };
  await rpc(author, "create_post", post);
  await rpc(author, "create_post", post);
  assert.equal((await rpc(friend, "get_feed", { p_tab: "new" })).length, 1, "friend sees it once");
  assert.equal((await rpc(stranger, "get_feed", { p_tab: "new" })).length, 0, "stranger does not");
  const liked = await rpc(friend, "set_post_like", { p_post: pid, p_liked: true });
  await rpc(friend, "set_post_like", { p_post: pid, p_liked: true });
  assert.equal(liked.like_count, 1);
  await expectError(rpc(stranger, "set_post_like", { p_post: pid, p_liked: true }), "not_found");
  const cid = await rpc(friend, "add_comment", { p_post: pid, p_body: "Harika" });
  assert.ok(cid);
  const notes = await as(author, "select kind from public.notifications where user_id = $1 order by created_at", [author]);
  assert.ok(notes.some((n) => n.kind === "comment"));
});

await test("storage policies bind files to their owner", async () => {
  const ok = await as(alice, "select app_private.storage_can_write('avatars', $1 || '/abcdefghijklmnop.jpg', $2) as ok", [alice, alice]);
  assert.equal(ok[0].ok, true);
  const bad = await as(alice, "select app_private.storage_can_write('avatars', $1 || '/abcdefghijklmnop.jpg', $2) as ok", [bob, alice]);
  assert.equal(bad[0].ok, false);
  const traversal = await as(alice, "select app_private.storage_can_write('avatars', $1 || '/../x/abcdefghijklmnop.jpg', $2) as ok", [alice, alice]);
  assert.equal(traversal[0].ok, false);
});

await test("anon can read only the bootstrap gate", async () => {
  const boot = await as(null, "select public.app_bootstrap(1) as r");
  assert.equal(boot[0].r.update_required, false);
  await expectError(as(null, "select public.list_friends()"), "permission denied");
  await expectError(as(null, "select * from public.profiles"), "permission denied");
});

await test("stale queue rows and sessions are cleaned up by the sweeper", async () => {
  const s1 = await newUser("sam");
  await rpc(s1, "join_queue", { p_mode: "voice", p_intent: "chat", p_alias: "Sam", p_languages: ["tr"], p_strict_language: false });
  await asAdminSql("update public.matchmaking_queue set heartbeat_at = now() - interval '5 minutes' where user_id = $1", [s1]);
  await asAdminSql("select app_private.sweep()");
  const q = await asAdminSql("select * from public.matchmaking_queue where user_id = $1", [s1]);
  assert.equal(q.length, 0);
});

console.log(`\n${passed} checks passed${process.exitCode ? " — with failures" : ""}`);
