/** Cached Codex credentials, using the pinned Pi Codex OAuth protocol, not SIWC. */
export const CODEX_TOKEN_URL = 'https://auth.openai.com/oauth/token';
export const CODEX_RESPONSES_URL = 'https://chatgpt.com/backend-api/codex/responses';
export const CODEX_CLIENT_ID = 'app_EMoamEEZ73f0CkXaXp7hrann';
// Catalog compatibility follows the currently traced Codex client protocol.
// Keep it centralized and fixed so callers cannot redirect or spoof discovery.
export const CODEX_CATALOG_COMPAT_VERSION = '0.162.1';
const REFRESH_MARGIN_MS = 60000;
const MAX_TOKEN_RESPONSE = 32768;

export interface CodexCredentials {
  accessToken: string;
  refreshToken: string;
  accountId: string;
  expiresAt: number;
}
export interface CodexCredentialInput {
  apiKey?: string;
  idToken?: string;
  accessToken?: string;
  refreshToken?: string;
  accountId?: string;
  expiresAt?: number;
}
export interface CodexCredentialUpdate extends CodexCredentials { oldRefreshToken: string }
type Updater = (update: CodexCredentialUpdate) => CodexCredentialInput | Promise<CodexCredentialInput>;
interface AuthState { updater?: Updater; pending?: Promise<CodexCredentials>; failure?: Error; onActivity?: ()=>void; secrets: Set<string> }
const states = new WeakMap<object, AuthState>();
function authState(credentials: CodexCredentialInput): AuthState {
  let state = states.get(credentials);
  if (!state) {state = {secrets: new Set()};states.set(credentials, state);}
  for (const value of [credentials.apiKey, credentials.idToken, credentials.accessToken, credentials.refreshToken, credentials.accountId])
    if (typeof value === 'string' && value) state.secrets.add(value);
  return state;
}
export function registerCodexCredentialUpdater(credentials: CodexCredentialInput, updater: Updater, onActivity?: ()=>void): void {
  const state = authState(credentials);state.updater = updater;state.onActivity = onActivity;
}
export function hasPendingCodexRefresh(credentials: CodexCredentialInput): boolean {return !!states.get(credentials)?.pending;}
function notifyActivity(state: AuthState): void {try {state.onActivity?.();} catch {/* Observation must not interrupt a rotated-token save. */}}
/** Includes rotated secrets remembered before mutating the shared credential object. */
export function redactProviderSecrets(text: string, credentials: CodexCredentialInput): string {
  let result = String(text);
  const variants = new Set(providerSecretVariants(credentials));
  for (const secret of [...variants].sort((a,b)=>b.length-a.length)) result = result.replaceAll(secret, '[redacted]');
  return result;
}
export function providerSecretVariants(credentials: CodexCredentialInput): string[] {
  return [...authState(credentials).secrets].flatMap(secret=>[secret,JSON.stringify(secret).slice(1,-1)]);
}
function authError(message: string): Error {return new Error(message);}

/** Structural claim checks only; the inference server verifies JWT signatures. */
export function codexAccessClaims(token: string): {accountId: string; expiresAt: number} {
  try {
    if (typeof token !== 'string' || token.length > 32768) throw 0;
    const pieces = token.split('.');
    if (pieces.length !== 3 || pieces.some(piece=>!piece || !/^[A-Za-z0-9_-]+$/.test(piece))) throw 0;
    const claims = JSON.parse(Buffer.from(pieces[1], 'base64url').toString('utf8'));
    const accountId = claims?.['https://api.openai.com/auth']?.chatgpt_account_id;
    if (typeof accountId !== 'string' || !/^[A-Za-z0-9._:-]{1,200}$/.test(accountId) ||
        typeof claims.exp !== 'number' || !Number.isSafeInteger(claims.exp) || claims.exp <= 0 || claims.exp > Math.floor(Number.MAX_SAFE_INTEGER/1000)) throw 0;
    return {accountId, expiresAt: claims.exp * 1000};
  } catch {throw authError('Invalid Codex access-token claims; sign in again.');}
}
function checked(credentials: CodexCredentialInput): CodexCredentials {
  const claims = codexAccessClaims(credentials.accessToken!);
  if (credentials.accountId !== claims.accountId) throw authError('Codex account does not match its access token; sign in again.');
  if (typeof credentials.refreshToken !== 'string' || !credentials.refreshToken || credentials.refreshToken.length > 16384 || /[\u0000-\u0020\u007f]/.test(credentials.refreshToken))
    throw authError('Codex refresh credential is unavailable; sign in again.');
  if (typeof credentials.expiresAt !== 'number' || !Number.isSafeInteger(credentials.expiresAt) || credentials.expiresAt <= 0)
    throw authError('Codex token expiry is unavailable; sign in again.');
  return {accessToken: credentials.accessToken!, refreshToken: credentials.refreshToken, accountId: claims.accountId, expiresAt: Math.min(credentials.expiresAt, claims.expiresAt)};
}
async function boundedJson(response: Response): Promise<any> {
  if (!response.body) throw authError('Codex refresh returned no credential data.');
  const reader = response.body.getReader();const chunks: Uint8Array[] = [];let size = 0;
  try {
    while (true) {
      const next = await reader.read();if (next.done) break;
      size += next.value.byteLength;
      if (size > MAX_TOKEN_RESPONSE) {await reader.cancel();throw 0;}
      chunks.push(next.value);
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {throw authError('Codex refresh returned invalid credential data; sign in again.');}
}
function waitForShared<T>(pending: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return pending;
  return new Promise((resolve,reject)=>{
    const abort = ()=>{cleanup();reject(authError('Request cancelled.'));};
    const cleanup = ()=>signal.removeEventListener('abort',abort);
    if (signal.aborted) {abort();return;}
    signal.addEventListener('abort',abort,{once:true});
    pending.then(value=>{cleanup();resolve(value);},()=>{cleanup();reject(authError('Codex refresh failed; sign in again.'));});
  });
}

export async function ensureCodexCredentials(credentials: CodexCredentialInput, options: {signal?: AbortSignal; fetch?: typeof fetch; now?: ()=>number} = {}): Promise<CodexCredentials> {
  if (options.signal?.aborted) throw authError('Request cancelled.');
  const state = authState(credentials);
  if (state.pending) return waitForShared(state.pending, options.signal);
  if (state.failure) throw state.failure;
  const current = checked(credentials);const now = options.now ?? Date.now;
  if (current.expiresAt > now() + REFRESH_MARGIN_MS) return current;
  if (!state.updater) throw authError('Secure Codex credential persistence is unavailable; sign in again.');
  // The shared refresh is independent of any one conversation's cancellation.
  // A rotated token must still be persisted when a waiter is cancelled.
  state.pending = (async()=>{
    try {
      const response = await (options.fetch ?? globalThis.fetch)(CODEX_TOKEN_URL, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000),
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: new URLSearchParams({grant_type:'refresh_token',refresh_token:current.refreshToken,client_id:CODEX_CLIENT_ID}),
      });
      if (!response.ok) {await response.body?.cancel();throw 0;}
      const data = await boundedJson(response);
      if (typeof data.access_token !== 'string' || typeof data.refresh_token !== 'string' ||
          typeof data.expires_in !== 'number' || !Number.isSafeInteger(data.expires_in) || data.expires_in <= 0 || data.expires_in > 31*86400) throw 0;
      // Remember even rejected/persistence-failed rotations before touching native storage.
      state.secrets.add(data.access_token);state.secrets.add(data.refresh_token);
      if (typeof data.id_token === 'string' && data.id_token) state.secrets.add(data.id_token);
      const rotated = checked({accessToken:data.access_token,refreshToken:data.refresh_token,accountId:current.accountId,expiresAt:now()+data.expires_in*1000});
      if (rotated.expiresAt <= now() + REFRESH_MARGIN_MS) throw 0;
      const saved = await state.updater!({...rotated,oldRefreshToken:current.refreshToken});
      authState(saved);for (const secret of authState(saved).secrets) state.secrets.add(secret);
      const persisted = checked(saved);
      if (persisted.accessToken !== rotated.accessToken || persisted.refreshToken !== rotated.refreshToken || persisted.accountId !== rotated.accountId || persisted.expiresAt <= now() + REFRESH_MARGIN_MS) throw 0;
      Object.assign(credentials, persisted);
      return persisted;
    } catch {
      // A dispatched refresh may have rotated a token even when its response was
      // lost. Latch failure for this credential object instead of replaying it.
      state.failure = authError('Codex refresh could not be securely completed; sign in again. No retry was attempted.');
      throw state.failure;
    }
  })();
  const pending = state.pending;
  notifyActivity(state);
  const settled = ()=>{if(state.pending === pending) state.pending = undefined;notifyActivity(state);};
  void pending.then(settled,settled);
  return waitForShared(pending, options.signal);
}
