import {setTimeout as delay} from 'node:timers/promises';
import {createVault} from './vault.mjs';
import {titlePrompt,shortTitle} from './chat-titles.js';
import {CODEX_CLIENT_ID,codexAccessClaims,registerCodexCredentialUpdater,ensureCodexCredentials,makeStream,modelFor,discoverProviderModels,normalizeContext,validateConnection} from './.generated/provider-runtime.mjs';

const AUTH='https://auth.openai.com';
const VERIFY=AUTH+'/codex/device';
const PROVIDERS=new Set(['codex-account','nebius','opencode','chatgpt-desktop']);
const DEFAULTS={'chatgpt-desktop':'gpt-6-instant','codex-account':'gpt-6-luna',nebius:'zai-org/GLM-5.3-Flash',opencode:'big-pickle'};
export class ProviderFailure extends Error {constructor(message,status=400){super(message);this.status=status;}}
function fail(message,status){throw new ProviderFailure(message,status);}
function provider(value){if(!PROVIDERS.has(value))fail('Choose Nebius, OpenCode, Codex, or ChatGPT desktop.');return value;}
function token(value,max=32768){if(typeof value!=='string'||!value||value.length>max||/[\u0000-\u0020\u007f]/.test(value))fail('Credential data is missing or malformed.');return value;}
export function parseAccount(document){
  const source=document?.tokens||document?.['openai-codex']||document;
  const accessToken=token(source?.accessToken||source?.access_token||source?.access);
  const refreshToken=token(source?.refreshToken||source?.refresh_token||source?.refresh,16384);
  let claims;
  try{
    const header=JSON.parse(Buffer.from(accessToken.split('.')[0],'base64url').toString('utf8'));
    if(typeof header.alg!=='string'||!header.alg||header.alg.toLowerCase()==='none')throw Error();
    claims=codexAccessClaims(accessToken);
  }catch{fail('Select a valid Codex auth.json with account and refresh tokens.');}
  const accountId=source.accountId||source.account_id||claims.accountId;
  if(accountId!==claims.accountId)fail('The Codex account does not match its access token.');
  const supplied=source.expiresAt??source.expires??claims.expiresAt;
  if(!Number.isSafeInteger(supplied)||supplied<=0)fail('The Codex token expiry is invalid.');
  return {authMode:'codex-account',accessToken,refreshToken,accountId,expiresAt:Math.min(supplied,claims.expiresAt)};
}
async function boundedJson(response,max=262144){
  if(!response.body)fail('The provider returned an empty response.',502);
  const reader=response.body.getReader();const chunks=[];let size=0;
  try{while(true){const next=await reader.read();if(next.done)break;size+=next.value.length;if(size>max){await reader.cancel();fail('The provider response exceeded the size limit.',502);}chunks.push(next.value);}
    try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{fail('The provider returned invalid data.',502);}
  }finally{reader.releaseLock();}
}

export async function createProviderService({directory,fetcher=fetch,wait=delay,onAuthState=()=>{},validateDraft=validateConnection}={}){
  const vault=await createVault(directory);
  let generation=0,loginController,titleController,auth={state:'idle'},activeRequests=0;
  const cached=new Map(),discovered=new Map();
  function mutable(){if(activeRequests)fail('Wait for the current provider request to finish.',409);titleController?.abort();}
  function stopLogin(){generation++;loginController?.abort();loginController=undefined;}
  function status(){return {providers:Object.fromEntries([...PROVIDERS].map(id=>[id,{configured:Boolean(vault.get(id)),kind:id==='codex-account'?'account':id==='chatgpt-desktop'?'desktop':'key',...(id==='chatgpt-desktop'?{endpoint:vault.get(id)?.endpoint||''}:{})}])),voice:{configured:Boolean(vault.get('deepgram')),deepgram:Boolean(vault.get('deepgram')),groq:Boolean(vault.get('groq'))},auth:{...auth}};}
  function notify(){try{onAuthState(status());}catch{/* Observers cannot change credential persistence. */}}
  function credentials(id){
    const saved=vault.get(id);if(!saved)fail(id==='codex-account'?'Sign in to Codex or import auth.json first.':'Save your provider key first.',409);
    if(!cached.has(id)){
      const current={...saved};cached.set(id,current);
      if(id==='codex-account')registerCodexCredentialUpdater(current,async update=>{
        await vault.update(state=>{
          if(state[id]?.refreshToken!==update.oldRefreshToken)fail('The account changed. Start the request again.',409);
          const {oldRefreshToken,...account}=update;state[id]={...state[id],...account,authMode:id};
        });return {...vault.get(id)};
      });
    }
    return cached.get(id);
  }
  async function authPost(path,body,form,signal){
    let response;
    try{response=await fetcher(AUTH+path,{method:'POST',redirect:'error',headers:{'Content-Type':form?'application/x-www-form-urlencoded':'application/json',Accept:'application/json'},body:form?new URLSearchParams(body):JSON.stringify(body),signal:AbortSignal.any([signal,AbortSignal.timeout(15000)])});}
    catch{fail('Codex sign-in could not connect. Check your connection and try again.',502);}
    const data=await boundedJson(response);return {status:response.status,data};
  }
  async function pollLogin(device,selected,controller){
    let interval=Math.max(1000,Number(device.interval)*1000);const deadline=Date.now()+15*60*1000;
    try{
      while(Date.now()<deadline){
        if(selected!==generation||controller.signal.aborted)return;
        const result=await authPost('/api/accounts/deviceauth/token',{device_auth_id:device.device_auth_id,user_code:device.user_code},false,controller.signal);
        if(selected!==generation||controller.signal.aborted)return;
        if(result.status===200){
          const code=token(result.data.authorization_code),verifier=token(result.data.code_verifier);
          const exchange=await authPost('/oauth/token',{grant_type:'authorization_code',client_id:CODEX_CLIENT_ID,code,code_verifier:verifier,redirect_uri:AUTH+'/deviceauth/callback'},true,controller.signal);
          if(selected!==generation||controller.signal.aborted)return;
          if(exchange.status!==200)fail('Codex authorization could not be completed. Start sign-in again.');
          const lifetime=exchange.data.expires_in;
          if(!Number.isSafeInteger(lifetime)||lifetime<=0||lifetime>366*86400)fail('Codex returned an invalid token expiry.');
          const account=parseAccount({...exchange.data,expiresAt:Date.now()+lifetime*1000});
          if(account.expiresAt<=Date.now())fail('Codex returned an expired token. Start sign-in again.');
          if(selected!==generation||controller.signal.aborted)return;
          await vault.update(state=>{state['codex-account']=account;},()=>{if(selected!==generation)fail('Sign-in was cancelled.');});
          if(selected!==generation)return;
          cached.delete('codex-account');discovered.delete('codex-account');auth={state:'connected'};notify();return;
        }
        const error=typeof result.data.error==='string'?result.data.error:result.data.error?.code;
        if(error==='slow_down')interval=Math.min(900000,interval+5000);
        else if(result.status!==403&&result.status!==404&&error!=='deviceauth_authorization_pending')fail('Codex sign-in was rejected or expired. Start again.');
        await wait(Math.min(interval,Math.max(1,deadline-Date.now())),undefined,{signal:controller.signal});
      }
      fail('Device sign-in expired after 15 minutes. Start again.');
    }catch(error){if(selected===generation&&!controller.signal.aborted){auth={state:'error',error:error instanceof ProviderFailure?error.message:'Codex sign-in failed. Start again.'};notify();}}
  }
  function config(input){
    const id=provider(input.provider);const model=input.model||DEFAULTS[id];
    if(typeof model!=='string'||!/^[A-Za-z0-9._:/+@-]{1,160}$/.test(model))fail('Enter a valid model ID.');
    const levels=new Set(['minimal','low','medium','high','xhigh','max','standard','extended']);
    const reasoningEffort=input.reasoning||'low';if(!levels.has(reasoningEffort))fail('Choose a valid reasoning effort.');
    const selected={mode:id,model,reasoningEffort,...(id==='chatgpt-desktop'?{endpoint:credentials(id).endpoint}:{})};
    const credential=credentials(id);credential.model=model;
    const proof=(discovered.get(id)||credential.modelCatalog||[]).find(entry=>entry.id===model);
    credential.modelSource=id==='codex-account'?'catalog':'manual';
    credential.reasoningEffortModel=proof?model:'';
    credential.reasoningEfforts=proof?.supportedReasoningLevels||[];
    if(id==='codex-account'&&reasoningEffort!=='low'&&!credential.reasoningEfforts.includes(reasoningEffort))fail('Discover this model and choose a supported reasoning effort first.');
    return {selected,credential};
  }
  return {
    status,
    async start(){
      mutable();stopLogin();const selected=generation,controller=new AbortController();loginController=controller;auth={state:'starting'};
      try{
        const {status:code,data}=await authPost('/api/accounts/deviceauth/usercode',{client_id:CODEX_CLIENT_ID},false,controller.signal);
        if(selected!==generation)fail('Sign-in was cancelled.');
        if(code===404)fail('Device sign-in is unavailable. Enable it in your account settings or import auth.json.');
        if(code!==200)fail('Device sign-in could not start. Check your account settings.');
        token(data.device_auth_id);if(typeof data.user_code!=='string'||!/^[A-Za-z0-9-]{3,64}$/.test(data.user_code))fail('Codex returned an invalid device code.');
        const seconds=Number(data.interval);if(!Number.isFinite(seconds)||seconds<0||seconds>900)fail('Codex returned an invalid polling interval.');
        auth={state:'pending',userCode:data.user_code,verificationUri:VERIFY};
        void pollLogin(data,selected,controller);
        return status();
      }catch(error){if(selected===generation)auth={state:'error',error:error instanceof ProviderFailure?error.message:'Codex sign-in failed.'};throw error;}
    },
    cancel(){stopLogin();auth={state:'cancelled'};return status();},
    async importAccount(document){mutable();const account=parseAccount(document);stopLogin();await vault.update(state=>{state['codex-account']=account;});cached.delete('codex-account');discovered.delete('codex-account');auth={state:'connected'};return status();},
    async saveKey(input){mutable();const id=provider(input.provider);if(id==='codex-account')fail('Codex uses account sign-in, not an API key.');const draft=id==='chatgpt-desktop'?{authMode:id,endpoint:input.endpoint,model:input.model||DEFAULTS[id]}:{authMode:id,apiKey:token(input.apiKey,16384),model:input.model||DEFAULTS[id]};activeRequests++;try{await validateDraft(draft,fetcher);await vault.update(state=>{state[id]=draft;});cached.delete(id);discovered.delete(id);return status();}catch{fail('Provider connection test failed. Check the connection, model access and quota. Settings were not saved.',502);}finally{activeRequests--;}},
    async disconnect(id){mutable();provider(id);if(id==='codex-account'){stopLogin();auth={state:'idle'};}await vault.update(state=>{delete state[id];});cached.delete(id);discovered.delete(id);return status();},
    async saveVoiceKey(apiKey,engine='deepgram'){if(!['deepgram','groq'].includes(engine))fail('Choose a supported speech engine.');const key=token(apiKey,512);await vault.update(state=>{state[engine]={apiKey:key};});return status();},
    async disconnectVoice(engine='deepgram'){if(!['deepgram','groq'].includes(engine))fail('Choose a supported speech engine.');await vault.update(state=>{delete state[engine];});return status();},
    async transcribe(audio,type,engine='deepgram'){
      if(!['deepgram','groq'].includes(engine))fail('Choose Deepgram or Groq for cloud transcription.');
      const key=vault.get(engine)?.apiKey;if(!key)fail(`Add your ${engine==='groq'?'Groq':'Deepgram'} key in Voice settings first.`,409);
      if(!['audio/webm','audio/ogg','audio/wav','audio/mp4'].includes(type.split(';')[0])||!audio.length||audio.length>10*1024*1024)fail('Record a supported audio clip smaller than 10 MB.');
      if(engine==='groq'){
        const form=new FormData();form.set('model','whisper-large-v3-turbo');form.set('language','en');form.set('response_format','json');
        form.set('file',new Blob([audio],{type}),`speech.${({'audio/wav':'wav','audio/ogg':'ogg','audio/mp4':'mp4'})[type.split(';')[0]]||'webm'}`);
        const response=await fetcher('https://api.groq.com/openai/v1/audio/transcriptions',{method:'POST',redirect:'error',headers:{Authorization:'Bearer '+key},body:form,signal:AbortSignal.timeout(30000)});
        if(!response.ok){await response.body?.cancel();fail(response.status===429?'Groq speech quota reached. Try later or use system speech.':'Groq could not transcribe this recording. Check your key and connection.',502);}
        const data=await boundedJson(response);if(typeof data.text!=='string'||!data.text.trim()||data.text.length>2000)fail('Groq returned an empty or oversized transcript.',502);return {text:data.text.trim()};
      }
      const response=await fetcher('https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true',{method:'POST',redirect:'error',headers:{Authorization:'Token '+key,'Content-Type':type},body:audio,signal:AbortSignal.timeout(60000)});
      if(!response.ok){await response.body?.cancel();fail('Deepgram could not transcribe this recording. Check your voice key and connection.',502);}
      const data=await boundedJson(response,1048576);const text=data.results?.channels?.[0]?.alternatives?.[0]?.transcript;
      if(typeof text!=='string'||!text.trim())fail('No speech was detected. Try speaking again.');return {text:text.slice(0,12000)};
    },
    async speak(text){
      const key=vault.get('deepgram')?.apiKey;if(!key)fail('Add your Deepgram key in Voice settings first.',409);
      if(typeof text!=='string'||!text.trim()||text.length>12000)fail('The spoken reply is empty or too long.');
      const response=await fetcher('https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3',{method:'POST',redirect:'error',headers:{Authorization:'Token '+key,'Content-Type':'application/json'},body:JSON.stringify({text}),signal:AbortSignal.timeout(60000)});
      if(!response.ok){await response.body?.cancel();fail('Deepgram could not create the spoken reply.',502);}
      const reader=response.body.getReader();const chunks=[];let size=0;
      try{while(true){const next=await reader.read();if(next.done)break;size+=next.value.length;if(size>16*1024*1024){await reader.cancel();fail('The spoken reply exceeded its size limit.',502);}chunks.push(next.value);}}finally{reader.releaseLock();}
      return Buffer.concat(chunks);
    },
    async models(input){if(activeRequests)fail('A provider request is already running.',409);const {selected,credential}=config({...input,reasoning:'low'});activeRequests++;try{if(selected.mode==='codex-account')await ensureCodexCredentials(credential,{fetch:fetcher});const models=await discoverProviderModels(selected,credential,fetcher);await vault.update(state=>{state[selected.mode].modelCatalog=models;});credential.modelCatalog=models;discovered.set(selected.mode,models);return models;}finally{activeRequests--;}} ,
    async title(input,signal){
      if(activeRequests)fail('A provider request is already running.',409);
      const {selected,credential}=config(input);if(typeof input.text!=='string'||!input.text.trim()||input.text.length>4000)fail('Invalid conversation title input.');
      titleController?.abort();const controller=new AbortController();titleController=controller;
      try{const context=normalizeContext({systemPrompt:titlePrompt,messages:[{role:'user',content:input.text,timestamp:Date.now()}]});const stream=await makeStream(selected,credential,'chat-title')(modelFor(selected,credential),context,{signal:AbortSignal.any([controller.signal,signal||new AbortController().signal,AbortSignal.timeout(20000)]),fetch:fetcher,maxTokens:40});const response=await stream.result();if(controller.signal.aborted||response.stopReason==='error'||response.stopReason==='aborted')fail('Could not name this conversation.',502);const text=response.content.filter(block=>block.type==='text').map(block=>block.text).join(' ');if(!text.trim())fail('The model returned no title.',502);return {title:shortTitle(text)};}finally{if(titleController===controller)titleController=undefined;}
    },
    async chat(input,signal){
      titleController?.abort();
      if(activeRequests)fail('A provider request is already running.',409);
      const {selected,credential}=config(input);
      if(!Array.isArray(input.messages)||!input.messages.length||input.messages.length>80)fail('Send between 1 and 80 messages.');
      let total=0;
      const messages=input.messages.map(message=>{
        if(!message||!['user','assistant'].includes(message.role)||typeof message.text!=='string'||!message.text.trim()||message.text.length>12000)fail('The conversation contains an invalid message.');
        total+=message.text.length;
        return message.role==='user'?{role:'user',content:message.text,timestamp:Date.now()}:{role:'assistant',content:[{type:'text',text:message.text}],api: selected.mode==='codex-account'?'openai-codex-responses':'openai-completions',provider:selected.mode,model:selected.model,timestamp:Date.now(),stopReason:'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
      });
      if(total>100000)fail('Start a new chat or shorten this conversation.');
      const instructions=typeof input.instructions==='string'?input.instructions.slice(0,4000):'';
      activeRequests++;
      try{
        const context={systemPrompt:'You are Pocket Agent. This browser session supports text conversations only. You have no access to phone tools, files, background tasks, or device permissions. Never claim to have used them.\n'+instructions,messages};
        const stream=await makeStream(selected,credential,'main')(modelFor(selected,credential),context,{signal,fetch:fetcher});
        const response=await stream.result();
        if(response.stopReason==='error'||response.stopReason==='aborted')fail(signal?.aborted?'Response cancelled.':'The provider could not complete this response. Check your connection, model access, or sign in again.',502);
        const text=response.content.filter(block=>block.type==='text').map(block=>block.text).join('\n');if(!text)fail('The provider returned no text.',502);
        return {text,provider:selected.mode,model:selected.model};
      }finally{activeRequests--;}
    },
    close(){titleController?.abort();stopLogin();}
  };
}
