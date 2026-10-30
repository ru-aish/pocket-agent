import {desktopModels} from './chatgpt-desktop.js';
import { getPiUserAgent } from '@earendil-works/pi-ai/utils/pi-user-agent';
import { modelFor, REASONING_EFFORTS, type ProviderConfig, type ProviderCredentials, type ReasoningEffort } from './provider.js';
import { CODEX_CATALOG_COMPAT_VERSION, ensureCodexCredentials } from './codex-auth.js';

export interface CatalogModel {
  id: string;
  name: string;
  supportedReasoningLevels?: ReasoningEffort[];
  defaultReasoningLevel?: ReasoningEffort;
}
export const CODEX_MODELS_CLIENT_VERSION = CODEX_CATALOG_COMPAT_VERSION;
export const CODEX_MODELS_ORIGINATOR = 'pi';
export const CODEX_MODELS_URL = 'https://chatgpt.com/backend-api/codex/models';
export const OPENCODE_MODELS_URL = 'https://opencode.ai/inference/v1/models';
const MAX_PROVIDER_CATALOG_BYTES = 512 * 1024;
// Codex entries include large instructions as well as model metadata.
const MAX_CODEX_CATALOG_BYTES = 4 * 1024 * 1024;
const MODEL_ID = /^[A-Za-z0-9._:/+@-]{1,160}$/;
const OPENCODE_NON_CHAT_FREE = new Set(['jev-1.13-free']);
const REASONING_LEVEL_SET = new Set<string>(REASONING_EFFORTS);

function catalogReasoning(entry: any): Pick<CatalogModel,'supportedReasoningLevels'|'defaultReasoningLevel'> {
  if (!entry || typeof entry !== 'object') return {};
  const hasAdvertisedLevels = Array.isArray(entry.supported_reasoning_levels);
  const advertised = hasAdvertisedLevels ? entry.supported_reasoning_levels : [];
  const supported: ReasoningEffort[] = [];
  const seen = new Set<string>();
  for (const item of advertised) {
    const effort = typeof item === 'string' ? item : item?.effort;
    if (typeof effort !== 'string' || !REASONING_LEVEL_SET.has(effort) || seen.has(effort)) continue;
    seen.add(effort);
    supported.push(effort as ReasoningEffort);
  }
  const rawDefault = entry.default_reasoning_level;
  const defaultReasoningLevel = typeof rawDefault === 'string'
    && REASONING_LEVEL_SET.has(rawDefault)
    && (!hasAdvertisedLevels || seen.has(rawDefault))
    ? rawDefault as ReasoningEffort : undefined;
  return {
    ...(supported.length ? {supportedReasoningLevels:supported} : {}),
    ...(defaultReasoningLevel ? {defaultReasoningLevel} : {}),
  };
}

/** Fetches provider-advertised models, never an inferred or hard-coded allowlist. */
export async function discoverProviderModels(
  config: ProviderConfig,
  credentials: ProviderCredentials,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<CatalogModel[]> {
  if (config.mode === 'scripted') return [{id: 'scripted', name: 'Scripted model'}];
  const current = modelFor(config, credentials); // Validates scheme/host/credential-free base URL.
  if(current.provider==='chatgpt-desktop')return desktopModels(current.baseUrl,fetcher);
  const account = current.api === 'openai-codex-responses';
  const maxCatalogBytes = account ? MAX_CODEX_CATALOG_BYTES : MAX_PROVIDER_CATALOG_BYTES;
  const mode = credentials.authMode || config.mode || 'openai';
  const auth = account ? await ensureCodexCredentials(credentials) : null;
  const token = account ? auth!.accessToken : credentials.apiKey;
  if (mode !== 'opencode' && !token?.trim()) throw new Error('Save a provider credential before discovering models.');
  const url = account ? `${CODEX_MODELS_URL}?client_version=${encodeURIComponent(CODEX_MODELS_CLIENT_VERSION)}` : mode === 'opencode' ? OPENCODE_MODELS_URL : current.baseUrl.replace(/\/$/, '') + '/models';
  const headers: Record<string,string> = {Accept: 'application/json'};
  // OpenCode's catalog is public; never send a Console service-account key to that separate public origin.
  if (mode !== 'opencode' && token?.trim()) headers.Authorization = 'Bearer ' + token;
  if (account) {
    headers['ChatGPT-Account-Id'] = auth!.accountId;
    // Current Codex catalog requests carry originator, User-Agent and package
    // version. Use Pi's own identity for the first two and never accept a
    // caller-controlled discovery URL.
    headers.originator = CODEX_MODELS_ORIGINATOR;
    headers['User-Agent'] = getPiUserAgent();
    headers.version = CODEX_MODELS_CLIENT_VERSION;
  }
  let response: Response;
  try {
    response = await fetcher(url, {method:'GET', headers, redirect:'error', signal:AbortSignal.timeout(15000)});
  } catch (error) {
    const code = (error as any)?.cause?.code ?? (error as any)?.code;
    const detail = ['ENOTFOUND','EAI_AGAIN','ECONNREFUSED','ECONNRESET','ETIMEDOUT','UND_ERR_CONNECT_TIMEOUT','CERT_HAS_EXPIRED','UNABLE_TO_VERIFY_LEAF_SIGNATURE'].includes(code) ? ` (${code})` : '';
    throw new Error('Provider model discovery could not connect' + detail + '. Check network access and the provider endpoint.');
  }
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error(response.status === 401
      ? 'Model discovery rejected this API key (HTTP 401). Check the key and selected provider.'
      : response.status === 403 ? 'Model discovery access denied (HTTP 403). Check provider permissions.'
      : 'Provider model discovery rejected the request (HTTP ' + response.status + ').');
  }
  const length = Number(response.headers.get('content-length'));
  if (length > maxCatalogBytes) {await response.body?.cancel();throw new Error('Provider model catalog exceeds size limit.');}
  if (!response.body) throw new Error('Provider model catalog was empty.');
  const chunks: Uint8Array[] = [];
  const reader = response.body.getReader();
  let count = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      count += next.value.byteLength;
      if (count > maxCatalogBytes) throw new Error('Provider model catalog exceeds size limit.');
      chunks.push(next.value);
    }
  } catch (error) {
    await reader.cancel().catch(()=>{});
    if (error instanceof Error && error.message.includes('size limit')) throw error;
    throw new Error('Provider model catalog could not be read.');
  }
  let body: any;
  try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new Error('Provider returned an invalid model catalog.'); }
  // Standard OpenAI-compatible APIs use data[].id; Codex uses models[].slug.
  const entries = Array.isArray(body?.data) ? body.data : body?.models;
  if (!Array.isArray(entries)) throw new Error('Provider does not expose a supported model catalog.');
  const seen = new Set<string>();
  const models: CatalogModel[] = [];
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object' || entry.supported_in_api === false || entry.visibility === 'hide') continue;
    const id = entry.id ?? entry.slug;
    if (typeof id !== 'string' || !MODEL_ID.test(id) || seen.has(id)) continue;
    // Nebius lists embedding/reranking endpoints alongside chat models.
    // These cannot be sent to the Chat Completions adapter.
    if (mode === 'nebius' && /(^|[/._-])(embedding\w*|rerank\w*)([/._-]|$)/i.test(id)) continue;
    if (mode === 'opencode' && ((id !== 'big-pickle' && !id.endsWith('-free')) || OPENCODE_NON_CHAT_FREE.has(id))) continue;
    seen.add(id);
    const display = entry.display_name ?? entry.name;
    models.push({
      id,
      name: typeof display === 'string' && display.trim() && display.length <= 120 ? display : id,
      ...catalogReasoning(entry),
    });
    if (models.length >= 1024) break;
  }
  if (!models.length) throw new Error('Provider returned no usable model identifiers.');
  return models;
}
