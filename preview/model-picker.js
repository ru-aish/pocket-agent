import {api} from './connection.js';
const effortOrder=['none','minimal','low','medium','high','xhigh','max'];
const effortNames={none:'None',minimal:'Minimal',low:'Low',medium:'Medium',high:'High',xhigh:'Extra high',max:'Maximum'};
export function compactModelName(name){const model=(name||'Model').split('/').at(-1).replace(/^gpt-/i,'GPT ');return model.length>12?model.slice(0,11)+'…':model;}
export function reasoningLevels(model){return effortOrder.filter(level=>model?.supportedReasoningLevels?.includes(level));}

// Cache successes and share an in-flight discovery. Failures can be retried.
export function createModelCatalog(load){
 const entries=new Map();
 return {get(provider){if(!entries.has(provider)){const pending=Promise.resolve().then(()=>load(provider)).catch(error=>{if(entries.get(provider)===pending)entries.delete(provider);throw error;});entries.set(provider,pending);}return entries.get(provider);},clear(){entries.clear();}};
}
export function mountModelPicker({getConfig,save,isBusy=()=>false,onConnect}) {
 function node(tag,className,text){const n=document.createElement(tag);if(className)n.className=className;if(text!==undefined)n.textContent=text;return n;}
 const trigger=node('button','model-picker-trigger');trigger.type='button';trigger.setAttribute('aria-haspopup','dialog');trigger.setAttribute('aria-expanded','false');document.querySelector('#voice-button').before(trigger);
 const panel=node('section','model-picker-popover');panel.hidden=true;panel.id='composer-model-picker';panel.setAttribute('role','dialog');panel.setAttribute('aria-label','Model and thinking effort');trigger.setAttribute('aria-controls',panel.id);document.querySelector('.workspace').append(panel);
 const list=node('div','model-picker-list');list.id='composer-model-list';list.hidden=true;list.setAttribute('role','group');list.setAttribute('aria-label','Available models');
 const header=node('button','model-picker-heading');header.type='button';header.setAttribute('aria-expanded','false');header.setAttribute('aria-controls',list.id);
 const name=node('span','model-picker-name'),effortText=node('span','model-picker-effort'),chevron=node('span','model-picker-chevron','›');chevron.setAttribute('aria-hidden','true');header.append(name,effortText,chevron);
 const track=node('div','model-picker-track'),ticks=node('div','model-picker-ticks'),slider=node('input','model-picker-slider');ticks.setAttribute('aria-hidden','true');slider.type='range';slider.min='0';slider.step='1';slider.setAttribute('aria-label','Thinking effort');track.append(ticks,slider);
 const status=node('p','model-picker-status');status.setAttribute('role','status');const retry=node('button','model-picker-retry','Retry');retry.type='button';retry.hidden=true;
 panel.append(list,header,track,status,retry);
 const catalog=createModelCatalog(async()=>{const result=await api('/api/provider/models',{...getConfig()});return result.models||[];});
 let generation=0,models=[],selected,effort,levels=[],saving=false,provider='',lastProvider=getConfig().provider;
 function position(){if(panel.hidden)return;const workspace=panel.parentElement.getBoundingClientRect(),composer=document.querySelector('#composer').getBoundingClientRect();panel.style.bottom=Math.max(8,workspace.bottom-composer.top+8)+'px';panel.style.setProperty('--model-panel-height',Math.max(80,composer.top-workspace.top-16)+'px');panel.style.setProperty('--model-list-height',Math.max(72,Math.min(240,composer.top-workspace.top-140))+'px');}
 function update(){const config=getConfig();if(config.provider!==lastProvider){lastProvider=config.provider;catalog.clear();if(!panel.hidden)close();}trigger.textContent=compactModelName(config.model);trigger.title=config.model||'Choose a model';trigger.setAttribute('aria-label',(config.model||'Model')+' · Choose model and thinking effort');}
 function showList(visible){list.hidden=!visible;header.setAttribute('aria-expanded',String(visible));position();}
 function close(){++generation;panel.hidden=true;showList(false);trigger.setAttribute('aria-expanded','false');}
 function paint(){
  name.textContent=selected?.name||selected?.id||getConfig().model||'Choose model';levels=reasoningLevels(selected);
  if(!levels.includes(effort))effort=levels.includes(selected?.defaultReasoningLevel)?selected.defaultReasoningLevel:levels[0];
  effortText.textContent=levels.length?' · '+effortNames[effort]:'';slider.max=String(Math.max(0,levels.length-1));slider.value=String(Math.max(0,levels.indexOf(effort)));slider.setAttribute('aria-valuetext',effortNames[effort]||'Not supported');slider.disabled=saving||isBusy()||levels.length<2;track.hidden=!levels.length;
  const fill=levels.length<2?0:100*Number(slider.value)/(levels.length-1);track.style.setProperty('--effort-fill',fill+'%');ticks.replaceChildren();for(let i=0;i<levels.length;i++)ticks.append(node('span'));
  header.disabled=saving||!models.length;for(const b of list.querySelectorAll('button')){b.disabled=saving||isBusy();b.setAttribute('aria-pressed',String(b.dataset.model===selected?.id));b.querySelector('.model-picker-check').textContent=b.dataset.model===selected?.id?'✓':'';}
  status.textContent=saving?'Saving…':isBusy()?'Stop current work to change models.':'';position();
 }
 async function commit(){
  if(saving||isBusy()||!selected){paint();return;}saving=true;const request=generation,chosen={...selected},reasoning=effort;paint();
  try{await save({provider,model:chosen.id,reasoning:reasoning||'low',...(levels.length?{supportedReasoningLevels:levels}:{})});update();if(request===generation)paint();}
  catch(error){if(request===generation){const config=getConfig();selected=models.find(m=>m.id===config.model)||{id:config.model};effort=config.reasoning;paint();status.textContent=error.message;}}
  finally{saving=false;if(request===generation){slider.disabled=isBusy()||levels.length<2;header.disabled=!models.length;for(const b of list.querySelectorAll('button'))b.disabled=isBusy();if(status.textContent==='Saving…')status.textContent='';}}
 }
 async function open(){
  if(!panel.hidden){close();return;}if(saving)return;update();const config={...getConfig()},request=++generation;provider=config.provider;models=[];selected={id:config.model};effort=config.reasoning;retry.hidden=true;list.replaceChildren();showList(false);panel.hidden=false;trigger.setAttribute('aria-expanded','true');paint();status.textContent='Loading models…';
  if(!provider||provider==='none'){status.textContent='Connect a provider to choose a model.';retry.textContent='Connect provider';retry.hidden=false;retry.onclick=()=>{close();onConnect();};return;}
  try{const result=await catalog.get(provider);if(request!==generation||panel.hidden)return;models=result;selected=models.find(m=>m.id===getConfig().model)||{id:getConfig().model};effort=getConfig().reasoning;
   for(const model of models){const b=node('button','model-picker-option');b.type='button';b.dataset.model=model.id;b.append(node('span','',model.name||model.id),node('span','model-picker-check'));b.onclick=()=>{selected=model;showList(false);paint();void commit();};list.append(b);}paint();if(!models.length)status.textContent='No models returned.';
  }catch(error){if(request===generation){status.textContent=error.message;retry.textContent='Retry';retry.hidden=false;retry.onclick=()=>{close();void open();};}}
 }
 slider.oninput=()=>{effort=levels[Number(slider.value)];effortText.textContent=' · '+effortNames[effort];slider.setAttribute('aria-valuetext',effortNames[effort]);track.style.setProperty('--effort-fill',(100*Number(slider.value)/Math.max(1,levels.length-1))+'%');};slider.onchange=()=>void commit();header.onclick=()=>showList(list.hidden);
 trigger.onpointerdown=event=>event.preventDefault();trigger.onclick=()=>void open();
 document.addEventListener('pointerdown',event=>{if(!panel.hidden&&!panel.contains(event.target)&&!trigger.contains(event.target))close();});
 document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!panel.hidden){event.preventDefault();event.stopImmediatePropagation();if(!list.hidden)showList(false);else{close();trigger.focus();}}},{capture:true});
 window.addEventListener('resize',position);window.visualViewport?.addEventListener('resize',position);window.visualViewport?.addEventListener('scroll',position);new ResizeObserver(position).observe(document.querySelector('#composer'));
 window.addEventListener('pocket-model-catalog-changed',()=>{catalog.clear();if(!panel.hidden)close();});
 update();return {open,close,update,isOpen:()=>!panel.hidden};
}
