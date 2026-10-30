import {discoverProviderModels} from './model-catalog.js';
import {makeStream,modelFor,type ProviderConfig,type ProviderCredentials} from './provider.js';

/** Tests a draft without replacing or persisting the active connection. */
export async function validateConnection(credentials: ProviderCredentials, fetcher: typeof fetch = globalThis.fetch) {
  const signal=AbortSignal.timeout(60000);
  const mode = credentials.authMode;
  if (!['chatgpt-desktop','nebius','opencode','openai','api-key','codex','codexRelay','codex-relay'].includes(mode ?? ''))
    throw new Error('Select an API-key provider to test.');
  const config: ProviderConfig = {mode:(mode === 'api-key' ? 'openai' : mode?.startsWith('codex') ? 'codex' : mode) as ProviderConfig['mode'],
    endpoint:credentials.endpoint,model:credentials.model};
  if (mode!=='chatgpt-desktop' && !credentials.apiKey?.trim()) throw new Error('Enter an API key before testing.');
  const model = modelFor(config,credentials);
  const models = await discoverProviderModels(config,credentials,fetcher,signal);
  if (!models.some(entry=>entry.id === model.id)) throw new Error('The selected model is not in this key’s catalog. Choose an available model and test again.');
  const stream = await makeStream(config,credentials,'connection-test')(model,{
    messages:[{role:'user',content:[{type:'text',text:'Reply with OK.'}],timestamp:Date.now()}],
  },{fetch:fetcher,signal});
  const result = await stream.result();
  if (result.stopReason === 'error' || result.stopReason === 'aborted') {
    // Do not return provider bodies or draft credentials through UI events/logs.
    const status = result.errorMessage?.match(/\b(401|403|404|429|5\d\d)\b/)?.[1];
    throw new Error(status === '401' ? 'Inference rejected this API key (HTTP 401). Check the key and provider.'
      : status === '403' ? 'Inference access denied (HTTP 403). Check model permissions and billing.'
      : status ? `Inference test failed (HTTP ${status}). Check model access, quota, and backend availability.`
      : 'Inference test failed or timed out. Check the connection and selected model.');
  }
  if (!result.content.some(part=>part.type === 'text' && part.text.trim())) throw new Error('Backend responded without a text answer. Try another model.');
  return {models,model:model.id};
}
