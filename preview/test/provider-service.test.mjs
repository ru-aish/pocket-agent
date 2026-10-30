import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,readFile,stat} from 'node:fs/promises';
import {join} from 'node:path';
import {createProviderService,parseAccount} from '../provider-service.mjs';
import {shortTitle} from '../chat-titles.js';

test('sidebar titles are short and model naming sends no phone tools',async t=>{
  assert.ok(Array.from(shortTitle('Can you help me plan a very very very very long complex week for my whole family')).length<=42);
  let requested;
  const {service}=await setup(t,{fetcher:async(url,init)=>{requested=JSON.parse(init.body);const chunks=[{id:'title',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{role:'assistant',content:'Weekly family planning'},finish_reason:null}]},{id:'title',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{},finish_reason:'stop'}]}];return new Response(chunks.map(chunk=>'data: '+JSON.stringify(chunk)+'\n\n').join('')+'data: [DONE]\n\n',{headers:{'Content-Type':'text/event-stream'}});}});
  await service.saveKey({provider:'nebius',apiKey:'fixture-key'});
  assert.equal((await service.title({provider:'nebius',model:'fixture-model',text:'Help me plan next week for my family'})).title,'Weekly family planning');
  assert.ok(!requested.tools||requested.tools.length===0);assert.equal(requested.messages.filter(message=>message.role==='system'||message.role==='developer').length,1);
});

function fixtureAccount(id='fixture-account',expiry=Date.now()+3600000){
  const access=[Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url'),Buffer.from(JSON.stringify({exp:Math.floor(expiry/1000),'https://api.openai.com/auth':{chatgpt_account_id:id}})).toString('base64url'),'fixture-signature'].join('.');
  return {access_token:access,refresh_token:'fixture-refresh-'+id,expires_in:3600};
}
async function setup(t,options={}){const directory=await mkdtemp('/tmp/pocket-provider-test-');const service=await createProviderService({directory,fetcher:async()=>{throw Error('Unexpected network request');},validateDraft:async()=>({}),...options});t.after(async()=>{service.close();await rm(directory,{recursive:true,force:true});});return {service,directory};}

test('failed draft validation preserves the saved key without exposing rejected credentials',async t=>{
  let reject=false;
  const {service}=await setup(t,{validateDraft:async()=>{if(reject)throw Error('fixture-private-rejected-key');}});
  await service.saveKey({provider:'nebius',apiKey:'fixture-saved-key'});reject=true;
  await assert.rejects(service.saveKey({provider:'opencode',apiKey:'fixture-private-rejected-key'}),error=>/Settings were not saved/.test(error.message)&&!error.message.includes('fixture-private'));
  assert.equal(service.status().providers.nebius.configured,true);
  assert.equal(service.status().providers.opencode.configured,false);
});

test('Codex device sign-in exchanges authorization and persists an encrypted usable account',async t=>{
  // Given: the same fixed device-code endpoints used by CodexAccountAuth.java.
  const account=fixtureAccount();let calls=0;const terminal=Promise.withResolvers();
  const {service,directory}=await setup(t,{onAuthState:state=>terminal.resolve(state),fetcher:async(url,init)=>{
    calls++;assert.equal(init.redirect,'error');assert.ok(init.signal);
    if(url.endsWith('/usercode')){assert.equal(JSON.parse(init.body).client_id,'app_EMoamEEZ73f0CkXaXp7hrann');return Response.json({device_auth_id:'fixture-device',user_code:'FIXTURE-CODE',interval:1});}
    if(url.endsWith('/deviceauth/token'))return Response.json({authorization_code:'fixture-authorization',code_verifier:'fixture-verifier'});
    assert.equal(url,'https://auth.openai.com/oauth/token');const body=new URLSearchParams(init.body);assert.equal(body.get('grant_type'),'authorization_code');assert.equal(body.get('redirect_uri'),'https://auth.openai.com/deviceauth/callback');return Response.json(account);
  }});
  // When: a user starts and completes device authorization.
  const pending=await service.start();assert.equal(pending.auth.state,'pending');await terminal.promise;
  // Then: only safe status is returned and the credential is encrypted at rest.
  assert.equal(service.status().providers['codex-account'].configured,true);assert.equal(calls,3);
  const publicState=JSON.stringify(service.status());assert.ok(!publicState.includes(account.access_token)&&!publicState.includes(account.refresh_token));
  const ciphertext=await readFile(join(directory,'credentials.enc'));assert.ok(!ciphertext.includes(Buffer.from(account.refresh_token)));assert.ok(!ciphertext.includes(Buffer.from(account.access_token)));
  assert.equal((await stat(join(directory,'credentials.enc'))).mode&0o777,0o600);assert.equal((await stat(join(directory,'vault.key'))).mode&0o777,0o600);
  const reopened=await createProviderService({directory});t.after(()=>reopened.close());assert.equal(reopened.status().providers['codex-account'].configured,true);
});

test('cancelled device flow cannot exchange or replace the existing account',async t=>{
  // Given: a saved account and a pending authorization request.
  const gate=Promise.withResolvers(),started=Promise.withResolvers();let exchanges=0;
  const {service}=await setup(t,{fetcher:async url=>{
    if(url.endsWith('/usercode'))return Response.json({device_auth_id:'fixture-device',user_code:'FIXTURE-CODE',interval:1});
    if(url.endsWith('/deviceauth/token')){started.resolve();await gate.promise;return Response.json({authorization_code:'late-code',code_verifier:'late-verifier'});}
    exchanges++;return Response.json(fixtureAccount('wrong-account'));
  }});
  await service.importAccount({tokens:fixtureAccount('original-account')});await service.start();await started.promise;
  // When: the user cancels before the delayed authorization resolves.
  service.cancel();gate.resolve();await new Promise(resolve=>setImmediate(resolve));
  // Then: the late response is ignored, and the prior account stays configured.
  assert.equal(service.status().auth.state,'cancelled');assert.equal(service.status().providers['codex-account'].configured,true);assert.equal(exchanges,0);
});

test('invalid imported account data cannot replace a valid account',async t=>{
  // Given: an existing account.
  const {service}=await setup(t);await service.importAccount({tokens:fixtureAccount()});
  // When: a mismatched account is imported.
  await assert.rejects(service.importAccount({tokens:{...fixtureAccount(),account_id:'mismatched'}}),/does not match/);
  // Then: the existing connection remains usable.
  assert.equal(service.status().providers['codex-account'].configured,true);
});

test('sign-out removes the encrypted account and blocks further chat',async t=>{
  // Given: a saved Codex account.
  const {service}=await setup(t);await service.importAccount({tokens:fixtureAccount()});
  // When: the user signs out.
  await service.disconnect('codex-account');
  // Then: the account is no longer available for inference.
  assert.equal(service.status().providers['codex-account'].configured,false);
  await assert.rejects(service.chat({provider:'codex-account',messages:[{role:'user',text:'Hello'}]}),/Sign in/);
});

test('Nebius chat uses the repository adapter and the saved key at its fixed endpoint',async t=>{
  // Given: a saved provider key and a real SSE response at the HTTP seam.
  let request;
  const {service}=await setup(t,{fetcher:async(url,init)=>{
    request={url,init};const chunks=[{id:'fixture',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{role:'assistant',content:'Fixture answer'},finish_reason:null}]},{id:'fixture',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{},finish_reason:'stop'}]}];
    return new Response(chunks.map(chunk=>'data: '+JSON.stringify(chunk)+'\n\n').join('')+'data: [DONE]\n\n',{headers:{'Content-Type':'text/event-stream'}});
  }});await service.saveKey({provider:'nebius',apiKey:'fixture-nebius-key'});
  // When: the user submits a conversation.
  const result=await service.chat({provider:'nebius',model:'fixture-model',messages:[{role:'user',text:'Hello'}]});
  // Then: inference and its response use the actual runtime adapter.
  assert.equal(result.text,'Fixture answer');assert.equal(request.url,'https://api.tokenfactory.nebius.com/v1/chat/completions');
  assert.equal(new Headers(request.init.headers).get('authorization'),'Bearer fixture-nebius-key');assert.equal(JSON.parse(request.init.body).model,'fixture-model');
});

test('Deepgram voice key enables transcription and speech at fixed endpoints',async t=>{
  // Given: a separately stored voice key and synthetic audio responses.
  const visited=[];const {service}=await setup(t,{fetcher:async(url,init)=>{
    visited.push(url);assert.equal(init.headers.Authorization,'Token fixture-voice-key');
    if(url.includes('/listen?'))return Response.json({results:{channels:[{alternatives:[{transcript:'Hello Pocket'}]}]}});
    assert.equal(JSON.parse(init.body).text,'Hello back');return new Response(Buffer.from('fixture-audio'),{headers:{'Content-Type':'audio/mpeg'}});
  }});await service.saveVoiceKey('fixture-voice-key');
  // When: a recorded voice turn is transcribed and a response synthesized.
  const transcript=await service.transcribe(Buffer.from('fixture-input'),'audio/webm');const audio=await service.speak('Hello back');
  // Then: both operations use the saved key without exposing it in status.
  assert.equal(transcript.text,'Hello Pocket');assert.equal(audio.toString(),'fixture-audio');assert.deepEqual(visited,['https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true','https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3']);assert.equal(service.status().voice.configured,true);assert.ok(!JSON.stringify(service.status()).includes('fixture-voice-key'));
});

test('account imports reject unsigned JWTs and malformed expiry',()=>{
  // Given: an unsigned account-shaped token.
  const account=fixtureAccount();const parts=account.access_token.split('.');parts[0]=Buffer.from(JSON.stringify({alg:'none'})).toString('base64url');
  // When / Then: import rejects it before storage or inference.
  assert.throws(()=>parseAccount({...account,access_token:parts.join('.')}),/valid Codex/);
  assert.throws(()=>parseAccount({...account,expiresAt:-1}),/expiry/);
});

test('Groq Whisper uses the phone-control multipart protocol and its own key',async t=>{
  // Given: separate Groq and Deepgram keys.
  let request;const {service}=await setup(t,{fetcher:async(url,init)=>{request={url,init};return Response.json({text:'A Groq transcript'});}});
  await service.saveVoiceKey('fixture-deepgram','deepgram');await service.saveVoiceKey('fixture-groq','groq');
  // When: a Groq recording is transcribed without any AI provider configured.
  const result=await service.transcribe(Buffer.from('fixture-audio'),'audio/webm','groq');
  // Then: the request follows the original Groq protocol, with no Deepgram dependency.
  assert.equal(result.text,'A Groq transcript');assert.equal(request.url,'https://api.groq.com/openai/v1/audio/transcriptions');
  assert.equal(request.init.headers.Authorization,'Bearer fixture-groq');assert.equal(request.init.body.get('model'),'whisper-large-v3-turbo');assert.equal(request.init.body.get('language'),'en');assert.equal(request.init.body.get('response_format'),'json');assert.equal(await request.init.body.get('file').text(),'fixture-audio');
  assert.equal(service.status().providers['codex-account'].configured,false);assert.equal(service.status().voice.groq,true);
});

test('selecting Groq never falls back to a configured Deepgram key',async t=>{
  // Given: Deepgram is configured, while Groq is not.
  let calls=0;const {service}=await setup(t,{fetcher:async()=>{calls++;throw Error('Unexpected dispatch');}});await service.saveVoiceKey('fixture-deepgram');
  // When: Groq speech is requested.
  await assert.rejects(service.transcribe(Buffer.from('fixture'),'audio/webm','groq'),error=>error.status===409);
  // Then: nothing is sent to either cloud provider.
  assert.equal(calls,0);
});

test('an expired imported Codex session refreshes durably before model discovery',async t=>{
  // Given: an expired account and a replacement token for the same account.
  let refreshes=0;const {service,directory}=await setup(t,{fetcher:async url=>{
    if(url==='https://auth.openai.com/oauth/token'){refreshes++;return Response.json(fixtureAccount('refresh-account'));}
    assert.ok(url.startsWith('https://chatgpt.com/backend-api/codex/models'));
    return Response.json({models:[{slug:'fixture-model',display_name:'Fixture model'}]});
  }});await service.importAccount({tokens:fixtureAccount('refresh-account',Date.now()-1000)});
  // When: account-scoped model discovery runs.
  const models=await service.models({provider:'codex-account'});
  // Then: the actual repo refresh adapter persists the replacement and returns the catalog.
  assert.equal(refreshes,1);assert.equal(models[0].id,'fixture-model');const reopened=await createProviderService({directory});t.after(()=>reopened.close());assert.equal(reopened.status().providers['codex-account'].configured,true);
});

test('Codex rejects unverified efforts and never trusts client-supplied reasoning proof',async t=>{
 const {service}=await setup(t);await service.importAccount({tokens:fixtureAccount()});
 await assert.rejects(service.chat({provider:'codex-account',model:'unverified',reasoning:'high',supportedReasoningLevels:['high'],messages:[{role:'user',text:'Hello'}]}),/Discover this model/);
});

test('verified reasoning catalog persists across companion restarts',async t=>{
 const {service,directory}=await setup(t,{fetcher:async()=>Response.json({models:[{slug:'verified',supported_reasoning_levels:[{effort:'low'},{effort:'high'}]}]})});
 await service.importAccount({tokens:fixtureAccount()});await service.models({provider:'codex-account'});
 let reached=false;const reopened=await createProviderService({directory,fetcher:async()=>{reached=true;return Response.json({error:{code:'invalid_token'}},{status:401});}});t.after(()=>reopened.close());
 await assert.rejects(reopened.chat({provider:'codex-account',model:'verified',reasoning:'high',messages:[{role:'user',text:'Hello'}]}),/provider could not complete/);
 assert.equal(reached,true);
});
