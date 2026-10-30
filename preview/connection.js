export async function api(path,body,{signal}={}){
  if(window.PocketNative)return window.pocketRequest(path,body||{},signal);
  const response=await fetch(path,{method:body===undefined?'GET':'POST',signal,headers:{'X-Pocket-Request':'1',...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  let result;try{result=await response.json();}catch{throw Error('The local connection service is unavailable. Refresh and try again.');}
  if(!response.ok)throw Error(result.error||'Connection request failed.');return result;
}
function node(tag,className,text){const value=document.createElement(tag);if(className)value.className=className;if(text!==undefined)value.textContent=text;return value;}
const storagePlace=window.PocketNative?'phone':'computer';
export function mountConnection(parent,{provider,getConfig,onModel,voiceOnly=false}){
  const root=node('section','connection-card');parent.append(root);let disposed=false,timer;
  const error=node('p','connection-error');error.setAttribute('role','alert');
  async function act(work){error.textContent='';try{const result=await work();if(!disposed)render(result);}catch(failure){if(!disposed)error.textContent=failure.message;}}
  function button(label,action,primary=false){const value=node('button',primary?'settings-primary':'connection-button',label);value.type='button';value.onclick=async()=>{value.disabled=true;await action();if(value.isConnected)value.disabled=false;};return value;}
  function render(state){
    clearTimeout(timer);root.replaceChildren();if(disposed)return;
    const configured=voiceOnly?state.voiceAccount:state.providers[provider]?.configured;
    root.append(node('h3','connection-title',provider==='codex-account'?'Codex account':provider==='nebius'?'Nebius connection':'OpenCode connection'));
    root.append(node('p','connection-state',configured?(provider==='codex-account'?`Signed in · tokens saved on this ${storagePlace}`:`Provider key saved on this ${storagePlace}`):'Not connected'));
    if(provider==='codex-account'){
      if(state.auth.state==='pending'||state.auth.state==='starting'){
        root.append(node('p','connection-help','Open OpenAI’s sign-in page and enter this code. This window will update when authorization completes.'));
        if(state.auth.userCode){
          root.append(node('div','device-code',state.auth.userCode));
          const link=node('a','settings-primary connection-link','Open OpenAI sign-in');link.href=state.auth.verificationUri;link.target=window.PocketNative?'_self':'_blank';link.rel='noopener noreferrer';root.append(link);
          root.append(button('Copy code',async()=>{try{if(window.PocketNative)await api('/native/copy',{text:state.auth.userCode});else await navigator.clipboard.writeText(state.auth.userCode);error.textContent='Code copied.';}catch{error.textContent='Select the displayed code and copy it.';}}));
        }
        root.append(button('Cancel sign-in',()=>act(()=>api('/api/auth/codex/cancel',{}))));
        timer=setTimeout(()=>act(()=>api('/api/provider/status')),1800);
      }else{
        if(state.auth.state==='error')root.append(node('p','connection-error',state.auth.error));
        root.append(button(configured?'Sign in again':'Sign in with ChatGPT',()=>act(()=>api('/api/auth/codex/start',{voiceOnly})),!configured));
        const label=node('label','connection-import connection-button','Import Codex auth.json');
        const file=node('input','sr-only');file.type='file';file.accept='.json,application/json';file.setAttribute('aria-label','Import Codex auth.json');
        file.onchange=()=>act(async()=>{
          const selected=file.files[0];if(!selected)return state;if(selected.size>262144)throw Error('Choose an auth.json smaller than 256 KB.');
          let document;try{document=JSON.parse(await selected.text());}catch{throw Error('Choose a valid Codex auth.json file.');}
          try{return await api('/api/auth/codex/import',{document});}finally{file.value='';}
        });label.append(file);if(!voiceOnly)root.append(label);
      }
    }else if(provider==='chatgpt-desktop'){
      const form=node('form','provider-key-form');const label=node('label','','Desktop endpoint');label.htmlFor='desktop-endpoint';const input=node('input');input.id='desktop-endpoint';input.type='url';input.required=true;input.placeholder='http://100.x.y.z:19238';input.value=state.providers[provider]?.endpoint||'';
      const save=node('button','settings-primary','Test & connect desktop');save.type='submit';form.append(label,input,save);
      form.onsubmit=event=>{event.preventDefault();save.disabled=true;void act(async()=>{try{return await api('/api/provider/key',{...getConfig(),provider,endpoint:input.value});}finally{if(save.isConnected)save.disabled=false;}});};root.append(form);
    }else{
      const form=node('form','provider-key-form');const label=node('label','','Provider key');label.htmlFor='provider-key';const input=node('input');input.id='provider-key';input.type='password';input.autocomplete='off';input.required=true;input.maxLength=16384;input.placeholder=provider==='nebius'?'Nebius API key':'OpenCode Console service-account key';
      const save=node('button','settings-primary',configured?'Test & replace provider key':'Test & save provider key');save.type='submit';
      form.onsubmit=event=>{event.preventDefault();save.disabled=true;void act(async()=>{try{return await api('/api/provider/key',{...getConfig(),provider,apiKey:input.value});}finally{input.value='';if(save.isConnected)save.disabled=false;}});};form.append(label,input,save);root.append(form);
    }
    if(configured&&!voiceOnly){
      root.append(button('Discover models',async()=>{
        error.textContent='Loading available models…';
        try{
          const result=await api('/api/provider/models',getConfig());if(disposed)return;
          const label=node('label','field-label','Available models');label.htmlFor='model-catalog';const select=node('select');select.id='model-catalog';
          const placeholder=node('option','','Choose a model');placeholder.value='';select.append(placeholder);
          for(const model of result.models){const option=node('option','',model.name||model.id);option.value=model.id;select.append(option);}select.value=result.models.some(m=>m.id===getConfig().model)?getConfig().model:'';
          select.onchange=()=>{const chosen=result.models.find(m=>m.id===select.value);if(chosen)onModel(chosen);};root.querySelector('.catalog-picker')?.remove();const field=node('div','catalog-picker');field.append(label,select);root.append(field);error.textContent=result.models.length?'':'No models were returned by this provider.';
        }catch(failure){if(!disposed)error.textContent=failure.message;}
      }));
      root.append(button(provider==='codex-account'?'Sign out of Codex':provider==='chatgpt-desktop'?'Disconnect desktop':'Remove saved key',()=>act(()=>api('/api/provider/disconnect',{provider}))));
    }
    if(configured&&voiceOnly)root.append(button('Disconnect voice account',()=>act(()=>api('/native/voice/disconnect-account',{}))));
    root.append(error);
    if(provider==='chatgpt-desktop')root.append(node('p','connection-help','Connect this device to Tailscale. The signed-in ChatGPT desktop runs on your VPS; PocketAgent owns phone tools, workers, and saved chat history. Keep its debug port private to your tailnet.'));
    else root.append(node('p','connection-help',window.PocketNative?(provider==='codex-account'?'Account tokens stay protected by Android Keystore and are refreshed by the Android runtime.':'Your key is protected by Android Keystore and tested with the selected model before saving. It is sent only to the provider’s fixed endpoint.'):(provider==='codex-account'?'Account tokens stay encrypted on this local server and are refreshed using the repo’s Codex adapter. They are not stored in the browser.':'Your key is encrypted on this local server and tested with the selected model before saving. It is sent only to the provider’s fixed inference endpoint.')));
  }
  root.append(node('p','connection-help','Checking saved connection…'),error);
  void act(()=>api('/api/provider/status'));
  return ()=>{disposed=true;clearTimeout(timer);};
}
export function mountVoiceConnection(parent,engine='deepgram'){
  const name=engine==='groq'?'Groq':'Deepgram';
  const root=node('section','connection-card');parent.append(root);let disposed=false;
  const status=node('p','connection-state','Checking voice connection…');const error=node('p','connection-error');error.setAttribute('role','alert');
  const form=node('form','provider-key-form');const label=node('label','',name+' API key');label.htmlFor=engine+'-key';const input=node('input');input.id=engine+'-key';input.type='password';input.autocomplete='off';input.required=true;input.maxLength=512;input.placeholder='Enter your '+name+' key';
  const save=node('button','settings-primary','Save voice key');save.type='submit';form.append(label,input,save);
  const remove=node('button','connection-button','Remove saved voice key');remove.type='button';remove.hidden=true;
  function update(state){if(disposed)return;status.textContent=state.voice[engine]?'Voice key saved · ready to use the microphone':'No voice key saved';remove.hidden=!state.voice[engine];}
  form.onsubmit=async event=>{event.preventDefault();save.disabled=true;error.textContent='';try{update(await api('/api/voice/key',{apiKey:input.value,engine}));}catch(failure){error.textContent=failure.message;}finally{input.value='';save.disabled=false;}};
  remove.onclick=async()=>{remove.disabled=true;try{update(await api('/api/voice/disconnect',{engine}));}catch(failure){error.textContent=failure.message;}finally{remove.disabled=false;}};
  root.append(node('h3','connection-title',name+' voice connection'),status,form,remove,error,node('p','connection-help',engine==='groq'?`Uses whisper-large-v3-turbo, matching phone-control. Your key is encrypted on this ${storagePlace}; recordings go only to Groq.`:`Your voice key is encrypted on this ${storagePlace}. Recordings go only to Deepgram.`));
  void api('/api/provider/status').then(update,()=>{if(!disposed)error.textContent='Could not load the voice connection.';});
  return ()=>{disposed=true;};
}
