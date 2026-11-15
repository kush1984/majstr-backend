# «Увійти через Google» (branch `google_signup` in both repos)

- **Status:** code done on the branch, NOT merged, NOT pushed to master. Needs the Google Cloud setup
  below and a test on real phones before merging.
- **Migration:** V210 (`users.google_sub` + partial unique index). V210, not V209: the majster-bot work
  holds V209. If master's highest number moves before this merges, renumber.
- **Why:** 48 % of visitors are on Android; the registration form loses people at email/password.
  Google sign-in is one tap, no password, the email is verified by Google.

## Owner's setup (once)

1. Google Cloud Console → a project «Majstr» → APIs & Services → **OAuth consent screen**: External,
   app name Majstr, logo, support email, privacy policy `https://majstr.pro/privacy`, scopes
   `openid email profile` only (non-sensitive → no Google review).
2. **Credentials → Create OAuth client ID → Web application.** Authorized JavaScript origins:
   `https://majstr.pro` (and `http://localhost:3000`, `http://localhost:5173` for dev). No redirect URIs
   (the button uses the popup flow and posts the ID token to our API).
3. Put the client id in **both**: Railway backend `GOOGLE_CLIENT_ID`, PWA build env
   `VITE_GOOGLE_CLIENT_ID`. Empty on either side = the feature is off (404 / no button).

## Backend

- `POST /api/auth/google` `{credential, ref?, promoCode?, utm*, referrerHost?, entryPath?}` → the same
  `AuthResponse` as login/register. Public path.
- `GoogleIdTokens` checks the ID token with jjwt: Google's JWKS (`oauth2/v3/certs`, cached 1 h, an unknown
  `kid` refetches at most once a minute, a failed fetch keeps the old keys), `aud` = our client id,
  `iss` = accounts.google.com, not expired, `email_verified = true`. Network only, no transaction.
- `AuthService.signInWithGoogle` — one door for sign-in AND registration:
  1. found by `google_sub` → sign in;
  2. else found by email → **link** (`google_sub` set, `email_verified = true`) and sign in; an email
     already bound to ANOTHER Google account is refused (401), never swapped;
  3. else a new account: verified email, Google name as `fullName`, a random bcrypt password he never
     needs («Забули пароль» sets one), consent stamped (the button's own line), first-touch attribution
     like registration, starter catalog seeded. A gmail alias of an existing password account is 409
     (`existsByEmailCanonical`), the same rule as registration. Disposable-domain policy applies.
- Tests: `GoogleIdTokensTest` (audience, forged signature, unverified email, foreign issuer, junk, the
  refetch floor), `GoogleSignInIntegrationTest` (new → verified + attributed; second time same account;
  same-email linking; another Google account refused; off without a client id). Contract snapshot has
  `GoogleSignInRequest`.

## PWA

- `GoogleSignIn` (login AND register pages, above the email form): loads
  `https://accounts.google.com/gsi/client` on demand, renders Google's own button («Продовжити з Google»,
  `locale: uk`), posts the credential + stored attribution to `/api/auth/google`, then `startSession` and
  home. A line «Продовжуючи з Google, ви погоджуєтесь з політикою конфіденційності», then «або поштою».
- **Not offered** (renders nothing) when `VITE_GOOGLE_CLIENT_ID` is empty, offline, or inside an app's
  own browser (`isInAppBrowser`: Facebook, Instagram, TikTok, Telegram, LINE, Android WebView) — Google
  refuses sign-in there (`disallowed_useragent`).
- CSP (`public/_headers`, report-only today): `script-src …/gsi/client`, `style-src …/gsi/style`,
  `connect-src …/gsi/`, `frame-src …/gsi/`.
- Tests: `GoogleSignIn.test` (credential → API → session; nothing without a client id),
  `inAppBrowser.test`.

## Still to do before merge (next agent / owner)

- [ ] Owner: the Google Cloud setup above; set both env vars on staging/prod.
- [ ] Real-device check: Android Chrome, iPhone Safari, the **installed PWA on iPhone** (popups there are
      flaky — if it fails, switch `initialize` to `ux_mode: 'redirect'` with a `login_uri` handled by the
      API, or hide the button in standalone mode on iOS), and a Facebook/Telegram link (button hidden).
- [ ] PostHog/analytics: a `registered` event for a Google-created account (`useRegister` tracks it for
      the email form only) — needs `AuthResponse` to say «new account», or a `created` flag.
- [ ] Privacy policy page: mention that signing in with Google shares the name and email with us.
- [ ] Bump the PWA version (minor) at merge; refresh `CLAUDE.md` «latest is V…».
- [ ] Full gates: `./gradlew build` on the branch, the PWA CI mirror.
