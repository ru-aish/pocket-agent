import test from 'node:test';
import assert from 'node:assert/strict';
import {CODEX_MODELS_CLIENT_VERSION,discoverProviderModels} from '../src/model-catalog.js';
import {modelFor} from '../src/provider.js';
import {resolveModel} from '../vendor/pi9/execute.js';

const base={mode:'openai' as const,endpoint:'https://example.test/api/v1',model:'main-large'};
const credentials={apiKey:'private-api-key',model:'main-large'};
const json=(payload:any,status=200)=>Response.json(payload,{status});

test('catalog discovers arbitrary OpenAI-compatible model IDs and rejects hidden, duplicate and malformed entries',async()=>{
 let calls=0;
 const models=await discoverProviderModels(base,credentials,async(input,init)=>{
   calls++;
   assert.equal(String(input),'https://example.test/api/v1/models');
   assert.equal(new Headers(init?.headers).get('authorization'),'Bearer private-api-key');
   assert.equal(init?.redirect,'error');
   return json({data:[
     {id:'vendor/cheap-model',name:'Low cost'},
     {id:'vendor/cheap-model',name:'duplicate'},
     {id:'large:reasoning',display_name:'Strong reasoner'},
     {id:'hidden',visibility:'hide'},
     {id:'bad model with spaces'},
     {id:'no-api',supported_in_api:false},
   ]});
 });
 assert.equal(calls,1);
 assert.deepEqual(models,[{id:'vendor/cheap-model',name:'Low cost'},{id:'large:reasoning',name:'Strong reasoner'}]);
 assert.equal(modelFor(base,credentials).id,'main-large');
 const chosen=modelFor(base,credentials,'vendor/cheap-model');
 assert.equal(chosen.id,'vendor/cheap-model');
 assert.equal(resolveModel('vendor/cheap-model',modelFor(base,credentials),{'vendor/cheap-model':chosen}).value.id,'vendor/cheap-model');
 assert.match(resolveModel('unlisted',modelFor(base,credentials),{'vendor/cheap-model':chosen}).error,/catalog/);
 assert.match(resolveModel('vendor/cheap-model',modelFor({...base,endpoint:'https://other.test/v1'},credentials),{'vendor/cheap-model':chosen}).error,/catalog/);
});

test('provider discovery rejects unauthorized, malformed, oversized and unsafe catalogs without leaking secrets',async()=>{
 await assert.rejects(()=>discoverProviderModels(base,credentials,async()=>new Response('private-api-key',{status:401})),(error:any)=>{
   const message=String(error?.message??error);
   assert.match(message,/HTTP 401/);
   assert.doesNotMatch(message,/private-api-key/);
   return true;
 });
 for(const body of [{other:[]},{data:'wrong'},{data:[]}]){
   await assert.rejects(()=>discoverProviderModels(base,credentials,async()=>json(body)),/catalog|identifiers/);
 }
 await assert.rejects(()=>discoverProviderModels(base,credentials,async()=>new Response('X'.repeat(512*1024+1))),/size limit/);
 await assert.rejects(()=>discoverProviderModels({...base,endpoint:'http://attacker.test/v1'},credentials,async()=>{throw Error('No request expected')}),/HTTPS/);
});

function accountCredentials() {
 const expiry=Date.now()+3600000;
 const accountId='account-123';
 const token=[Buffer.from('{}').toString('base64url'),Buffer.from(JSON.stringify({exp:Math.floor(expiry/1000),'https://api.openai.com/auth':{chatgpt_account_id:accountId}})).toString('base64url'),'signature'].join('.');
 return {authMode:'codex-account',accessToken:token,refreshToken:'private-refresh',accountId,expiresAt:expiry};
}
test('Codex account discovery uses fixed origin, account-scoped authorization and advertised slugs',async()=>{
 const auth=accountCredentials();
 const models=await discoverProviderModels({mode:'codex-account',model:'main-large'},auth,async(input,init)=>{
   assert.equal(String(input),'https://chatgpt.com/backend-api/codex/models?client_version='+CODEX_MODELS_CLIENT_VERSION);
   const h=new Headers(init?.headers);
   assert.equal(h.get('authorization'),'Bearer '+auth.accessToken);
   assert.equal(h.get('chatgpt-account-id'),auth.accountId);
   assert.equal(h.get('originator'),'pocket-agent');
   assert.equal(h.get('user-agent'),'PocketAgent');
   assert.equal(init?.redirect,'error');
   return json({models:[
     {slug:'codex-strong',display_name:'Strong',default_reasoning_level:'medium',supported_reasoning_levels:[{effort:'low'},{effort:'medium'},{effort:'high'},{effort:'max'}]},
     {slug:'codex-fast',display_name:'Fast',default_reasoning_level:'low',supported_reasoning_levels:[{effort:'low'},{effort:'xhigh'}]},
     {slug:'unavailable',supported_in_api:false},
   ]});
 });
 assert.deepEqual(models,[
   {id:'codex-strong',name:'Strong',reasoningLevels:['low','medium','high'],defaultReasoning:'medium'},
   {id:'codex-fast',name:'Fast',reasoningLevels:['low','xhigh'],defaultReasoning:'low'},
 ]);
});


test('OpenCode discovery uses the fixed catalog, permits anonymous reads and exposes only free models',async()=>{
 const config={mode:'opencode' as const,model:'big-pickle'};
 const anonymous={authMode:'opencode',apiKey:'',model:'big-pickle'};
 const models=await discoverProviderModels(config,anonymous,async(input,init)=>{
   assert.equal(String(input),'https://opencode.ai/inference/v1/models');
   assert.equal(new Headers(init?.headers).get('authorization'),null);
   return json({data:[
     {id:'big-pickle'},
     {id:'beam-free'},
     {id:'nemotron-3.5-lightning-free'},
     {id:'jev-1.13-free'},
     {id:'gpt-6-sol'},
     {id:'paid-model'},
   ]});
 });
 assert.deepEqual(models,[{id:'big-pickle',name:'big-pickle'},{id:'beam-free',name:'beam-free'},{id:'nemotron-3.5-lightning-free',name:'nemotron-3.5-lightning-free'}]);
 let leaked=false;
 await discoverProviderModels(config,{...anonymous,apiKey:'opencode-secret'},async(_input,init)=>{
   leaked=new Headers(init?.headers).has('authorization');
   return json({data:[{id:'beam-free'}]});
 });
 assert.equal(leaked,false,'OpenCode service-account keys must not be sent to the public catalog');
});

test('Nebius discovery uses its fixed OpenAI-compatible endpoint and bearer key',async()=>{
 const config={mode:'nebius' as const,model:'zai-org/GLM-5.3-Flash'};
 const credentials={authMode:'nebius',apiKey:'nebius-secret',model:'zai-org/GLM-5.3-Flash'};
 const models=await discoverProviderModels(config,credentials,async(input,init)=>{
   assert.equal(String(input),'https://api.tokenfactory.nebius.com/v1/models');
   assert.equal(new Headers(init?.headers).get('authorization'),'Bearer nebius-secret');
   return json({data:[{id:'zai-org/GLM-5.3-Flash'}]});
 });
 assert.deepEqual(models,[{id:'zai-org/GLM-5.3-Flash',name:'zai-org/GLM-5.3-Flash'}]);
});
