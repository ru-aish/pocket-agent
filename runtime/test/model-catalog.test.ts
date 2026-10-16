import test from 'node:test';
import assert from 'node:assert/strict';
import {discoverProviderModels} from '../src/model-catalog.js';
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
 const failure=await assert.rejects(()=>discoverProviderModels(base,credentials,async()=>new Response('private-api-key',{status:401})),/HTTP 401/);
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
   assert.match(String(input),/^https:\/\/chatgpt\.com\/backend-api\/codex\/models\?/);
   const h=new Headers(init?.headers);
   assert.equal(h.get('authorization'),'Bearer '+auth.accessToken);
   assert.equal(h.get('chatgpt-account-id'),auth.accountId);
   assert.equal(init?.redirect,'error');
   return json({models:[{slug:'codex-strong',display_name:'Strong'},{slug:'codex-fast',display_name:'Fast'},{slug:'unavailable',supported_in_api:false}]});
 });
 assert.deepEqual(models,[{id:'codex-strong',name:'Strong'},{id:'codex-fast',name:'Fast'}]);
});
