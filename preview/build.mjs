import { build } from '../runtime/node_modules/esbuild/lib/main.js';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=dirname(fileURLToPath(import.meta.url));
export async function buildProviderRuntime(){
  await build({stdin:{contents:`export {makeStream,modelFor} from './src/provider.ts'; export {validateConnection} from './src/connection-validation.ts'; export {normalizeContext} from '@earendil-works/pi-ai/utils/transcript'; export {discoverProviderModels} from './src/model-catalog.ts'; export {CODEX_CLIENT_ID,codexAccessClaims,registerCodexCredentialUpdater,ensureCodexCredentials} from './src/codex-auth.ts';`,resolveDir:join(root,'../runtime')},outfile:join(root,'.generated/provider-runtime.mjs'),bundle:true,platform:'node',format:'esm',banner:{js:`import {createRequire} from 'node:module'; const require=createRequire(import.meta.url);`},nodePaths:[join(root,'../runtime/node_modules')]});
}
