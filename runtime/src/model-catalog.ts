import { modelFor, REASONING_LEVELS, type ProviderConfig, type ProviderCredentials, type ReasoningLevel } from './provider.js';
import { ensureCodexCredentials } from './codex-auth.js';

export interface CatalogModel { id: string; name: string; reasoningLevels?: ReasoningLevel[]; defaultReasoning?: ReasoningLevel }
const CODEX_MODELS_URL = 'https://chatgpt.com/backend-api/codex/models';
export const CODEX_MODELS_CLIENT_VERSION = '0.162.1';
export const OPENCODE_MODELS_URL = 'https://opencode.ai/inference/v1/models';
const MAX_CATALOG_BYTES = 512 * 1024;
const MODEL_ID = /^[A-Za-z0-9._:/+@-]{1,160}$/;
const OPENCODE_NON_CHAT_FREE = new Set(['jev-1.13-free']);

/** Fetches provider-advertised models, never an inferred or hard-coded allowlist. */
export async function discoverProviderModels(
  config: ProviderConfig,
  credentials: ProviderCredentials,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<CatalogModel[]> {
  if (config.mode === 'scripted') return [{id: 'scripted', name: 'Scripted model'}];
  const current = modelFor(config, credentials); // Validates scheme/host/credential-free base URL.
  const account = current.api === 'openai-codex-responses';
  const mode = credentials.authMode || config.mode || 'openai';
  const auth = account ? await ensureCodexCredentials(credentials) : null;
  const token = account ? auth!.accessToken : credentials.apiKey;
  if (mode !== 'opencode' && !token?.trim()) throw new Error('Save a provider credential before discovering models.');
  const url = account ? CODEX_MODELS_URL + '?client_version=' + encodeURIComponent(CODEX_MODELS_CLIENT_VERSION) : mode === 'opencode' ? OPENCODE_MODELS_URL : current.baseUrl.replace(/\/$/, '') + '/models';
  const headers: Record<string,string> = {Accept: 'application/json'};
  // OpenCode's catalog is public; never send a Console service-account key to that separate public origin.
  if (mode !== 'opencode' && token?.trim()) headers.Authorization = 'Bearer ' + token;
  if (account) {
    headers['ChatGPT-Account-Id'] = auth!.accountId;
    // Match the current Codex backend catalog contract without pretending to be the Codex CLI.
    headers.Originator = 'pocket-agent';
    headers['User-Agent'] = 'PocketAgent';
  }
  let response: Response;
  try {
    response = await fetcher(url, {method:'GET', headers, redirect:'error', signal:AbortSignal.timeout(15000)});
  } catch {
    throw new Error('Provider model discovery could not connect.');
  }
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error('Provider model discovery rejected the request (HTTP ' + response.status + ').');
  }
  const length = Number(response.headers.get('content-length'));
  if (length > MAX_CATALOG_BYTES) {await response.body?.cancel();throw new Error('Provider model catalog exceeds size limit.');}
  if (!response.body) throw new Error('Provider model catalog was empty.');
  const chunks: Uint8Array[] = [];
  const reader = response.body.getReader();
  let count = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      count += next.value.byteLength;
      if (count > MAX_CATALOG_BYTES) throw new Error('Provider model catalog exceeds size limit.');
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
    if (mode === 'opencode' && ((id !== 'big-pickle' && !id.endsWith('-free')) || OPENCODE_NON_CHAT_FREE.has(id))) continue;
    seen.add(id);
    const display = entry.display_name ?? entry.name;
    const advertised = Array.isArray(entry.supported_reasoning_levels) ? entry.supported_reasoning_levels
      : Array.isArray(entry.supported_reasoning_efforts) ? entry.supported_reasoning_efforts : [];
    const reasoningLevels = advertised
      .map((value:any)=>typeof value==='string'?value:value?.effort)
      .filter((value:any,index:number,all:any[])=>typeof value==='string'
        && (REASONING_LEVELS as readonly string[]).includes(value) && all.indexOf(value)===index) as ReasoningLevel[];
    const rawDefault = entry.default_reasoning_level ?? entry.default_reasoning_effort;
    const defaultReasoning = typeof rawDefault==='string'
      && (REASONING_LEVELS as readonly string[]).includes(rawDefault) ? rawDefault as ReasoningLevel : undefined;
    models.push({
      id,
      name: typeof display === 'string' && display.trim() && display.length <= 120 ? display : id,
      ...(reasoningLevels.length?{reasoningLevels}:{}),
      ...(defaultReasoning?{defaultReasoning}:{}),
    });
    if (models.length >= 1024) break;
  }
  if (!models.length) throw new Error('Provider returned no usable model identifiers.');
  return models;
}
