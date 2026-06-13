# Fix K — Admin user search 500 (`function lower(bytea) does not exist`)

- **Status:** ✅ Backend code + test complete — `./gradlew build` pending a local run.
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none.
- **Source:** production 500 on admin user search (`GET /api/admin/users?search=...`).

## Symptom

```
ERROR: function lower(bytea) does not exist
  ... lower(('%'||?||'%')) ...
```

## Root cause

`UserRepository.searchAdmin` matched with JPQL
`LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))`. The `:search` parameter
sits **inside** `CONCAT`/`LOWER`, so Hibernate has no surrounding operand to
infer its SQL type from and falls back to its default binding — effectively
`bytea`. PostgreSQL then evaluates `lower('%' || $1 || '%')` with `$1` typed
`bytea`, and there is no `lower(bytea)` function → the query 500s. (The same
parameter also appearing in `:search IS NULL` didn't help Hibernate pick text.)

## Fix

Build the pattern in Java and compare it as a **plain text LIKE operand**, so the
bind parameter is unambiguously text — no parameter is nested inside a function.

**Before**
```java
@Query("""
        ... AND ( :search IS NULL
          OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
          OR ... ) """)
Page<User> searchAdmin(Plan plan, String search, Pageable pageable);
```

**After**
```java
default Page<User> searchAdmin(Plan plan, String search, Pageable pageable) {
    return searchAdminByPattern(plan, likePattern(search), pageable);
}

static String likePattern(String search) {
    if (search == null || search.isBlank()) return null;
    return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
}

@Query("""
        ... AND ( :pattern IS NULL
          OR LOWER(u.email) LIKE :pattern
          OR LOWER(u.fullName) LIKE :pattern
          OR LOWER(u.companyName) LIKE :pattern ) """)
Page<User> searchAdminByPattern(Plan plan, String pattern, Pageable pageable);
```

- `searchAdmin`'s public signature is unchanged, so `AdminUserController` (its
  only caller) needs no edit.
- Case-insensitive: term lowercased in Java, column lowercased by SQL `LOWER`.
- Blank/null search → `null` pattern → search clause skipped (all users, within
  the plan filter), preserving the previous behaviour.

### Plan filter — left as-is (already correctly typed)

`(:plan IS NULL OR u.plan = :plan)` was **not** affected: `:plan` is a typed
`Plan` enum used in a direct `=` comparison, so Hibernate binds it with the
enum's known mapping — no untyped-parameter/bytea problem. Works alone and
combined with search.

## Tests

`UserRepositorySearchTest` (pure unit):
- `likePattern` → null for null/blank; `%term%` lowercased + trimmed (incl.
  Cyrillic, email).
- `searchAdmin` with text delegates `%acme%` + plan to `searchAdminByPattern`;
  blank text delegates a `null` pattern (→ all users). Covers text/no-text and
  plan/no-plan combinations at the Java seam.

Full SQL-execution coverage (the actual `lower()`/LIKE against Postgres) needs
the Testcontainers integration slice that isn't wired yet — tracked in
docs/open-questions.md; this is another concrete case for it.

## Verify (after local build)

`./gradlew build` green, then in the admin UI:
- search by partial email / name / company (case-insensitive) → results, no 500.
- empty search → all users; with a plan filter → only that plan.
- plan filter alone and combined with search → correct subset.
