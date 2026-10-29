import {api} from './connection.js';
const providers=[['codex-account','Codex'],['nebius','Nebius'],['opencode','OpenCode']];
const effortOrder=['none','minimal','low','medium','high','xhigh','max'];
export function compactModelName(name){const model=(name||'Model').split('/').at(-1).replace(/^gpt-/i,'GPT ');return model.length>12?model.slice(0,11)+'…':model;}
export function reasoningLevels(model){return effortOrder.filter(level=>model?.supportedReasoningLevels?.includes(level));}
export function mountModelPicker({getConfig,save,isBusy=()=>false,onConnect}) {
 const trigger=document.createElement('button');trigger.type='button';trigger.className='model-picker-trigger';trigger.setAttribute('aria-label','Choose model and thinking effort');trigger.setAttribute('aria-haspopup','dialog');
 document.querySelector('#voice-button').before(trigger);
 const dialog=document.createElement('dialog');dialog.className='model-picker-dialog';dialog.setAttribute('aria-label','Model and thinking effort');document.querySelector('.workspace').append(dialog);
 let generation=0;
 function update(){const config=getConfig();trigger.textContent=compactModelName(config.model);trigger.title=config.model||'Choose a model';trigger.setAttribute('aria-label',(config.model||'Model')+' · Choose model and thinking effort');}
 function close(){++generation;dialog.close();trigger.setAttribute('aria-expanded','false');}
 dialog.addEventListener('cancel',event=>{event.preventDefault();close();});dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)close();}});
 function node(tag,text){const n=document.createElement(tag);if(text)n.textContent=text;return n;}
 async function open(){
  const request=++generation,config={...getConfig()};dialog.replaceChildren();dialog.showModal();trigger.setAttribute('aria-expanded','true');
  const header=node('div');header.className='dialog-header';header.append(node('h2','Model & thinking'));const dismiss=node('button','Done');dismiss.type='button';dismiss.onclick=close;header.append(dismiss);dialog.append(header);
  const status=node('p',isBusy()?'Stop the current work before changing models.':'Loading available models…');status.setAttribute('role','status');
  const providerLabel=node('label','Provider'),provider=node('select');providerLabel.append(provider);
  for(const[id,name]of providers){const option=node('option',name+(id===config.provider?' · connected':' · connect in Settings'));option.value=id;option.disabled=id!==config.provider;provider.append(option);}provider.value=config.provider;
  dialog.append(providerLabel,status);
  if(!providers.some(([id])=>id===config.provider)){const connect=node('button','Connect a provider');connect.type='button';connect.onclick=()=>{close();onConnect();};dialog.append(connect);status.textContent='Connect a provider to see its models.';return;}
  try{
   const result=await api('/api/provider/models',config);if(request!==generation||!dialog.open)return;
   const models=result.models||[],label=node('label','Model'),select=node('select');select.setAttribute('aria-label','Model');
   for(const model of models){const option=node('option',model.name||model.id);option.value=model.id;select.append(option);}select.value=models.some(m=>m.id===config.model)?config.model:models[0]?.id||'';label.append(select);
   const effortLabel=node('label'),value=node('span'),slider=node('input');slider.type='range';slider.min='0';slider.step='1';slider.setAttribute('aria-label','Thinking effort');effortLabel.className='thinking-effort';effortLabel.append(value,slider);
   let levels=[],effort=config.reasoning;
   function choose(){const model=models.find(m=>m.id===select.value);levels=reasoningLevels(model);if(!levels.includes(effort))effort=levels.includes(model?.defaultReasoningLevel)?model.defaultReasoningLevel:levels[0];slider.max=String(Math.max(0,levels.length-1));slider.value=String(Math.max(0,levels.indexOf(effort)));slider.disabled=levels.length<2||isBusy();value.textContent=levels.length?'Thinking effort · '+effort:'Thinking effort · not supported';slider.setAttribute('aria-valuetext',effort||'Not supported');}
   slider.oninput=()=>{effort=levels[Number(slider.value)];value.textContent='Thinking effort · '+effort;slider.setAttribute('aria-valuetext',effort);};select.onchange=choose;choose();
   const apply=node('button','Use model');apply.type='button';apply.className='settings-primary';apply.disabled=!models.length||isBusy();select.disabled=isBusy();
   apply.onclick=async()=>{apply.disabled=true;select.disabled=true;slider.disabled=true;status.textContent='Saving…';try{await save({provider:config.provider,model:select.value,reasoning:effort||'low',...(levels.length?{supportedReasoningLevels:levels}:{})});update();close();}catch(error){if(request===generation){status.textContent=error.message;apply.disabled=isBusy();select.disabled=isBusy();slider.disabled=levels.length<2||isBusy();}}};
   dialog.append(label,effortLabel,apply);status.textContent=isBusy()?'Stop the current work before changing models.':models.length?'':'This provider returned no models.';
  }catch(error){if(request===generation)status.textContent=error.message;}
 }
 trigger.onclick=()=>void open();update();return {open,close,update,isOpen:()=>dialog.open};
}
