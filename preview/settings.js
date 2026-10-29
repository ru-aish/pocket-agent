import {mountConnection,mountVoiceConnection} from './connection.js';
const defaults = {name:'',provider:'none',model:'',reasoning:'low',instructions:'',stt:'android',wake:false,autonomy:false,servers:[]};
const providers = [['none','Not configured'],['nebius','Nebius Token Factory'],['opencode','OpenCode Free'],['codex-account','Codex account']];
const composerLabels = {none:'Pocket Agent',nebius:'Nebius',opencode:'OpenCode','codex-account':'Codex'};
const glyphs = {back:'m14 6-6 6 6 6',arrow:'m9 6 6 6-6 6',user:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2',model:'M7 3h10v18H7V3ZM3 8h4M17 8h4M3 16h4M17 16h4M10 8h4M10 12h4M10 16h2',edit:'m15 4 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14v6Z',voice:'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0V5ZM5 11v1a7 7 0 0 0 14 0v-1M12 19v3',phone:'M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM10 17h4',link:'m10 14 4-4M8 16l-2 2a4 4 0 0 1-6-6l4-4M16 8l2-2a4 4 0 0 1 6 6l-4 4',activity:'M3 12h4l3-8 4 16 3-8h4',look:'M12 3a9 9 0 1 0 0 18V3Z',plus:'M12 5v14M5 12h14'};
function el(tag, className, text) { const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node; }
function icon(name) { const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',glyphs[name]||glyphs.user);svg.append(path);return svg; }
export function createSettings({screen,onClose,getTheme,setTheme,onProviderChange}) {
  let prefs={...defaults,servers:[]};
  let storageAvailable=true;
  try {
    const saved=JSON.parse(localStorage.getItem('pocket-preview-settings')||'{}');
    for(const key of ['name','provider','model','reasoning','instructions','stt']) if(typeof saved?.[key]==='string')prefs[key]=saved[key];
    for(const key of ['wake','autonomy']) if(typeof saved?.[key]==='boolean')prefs[key]=saved[key];
    if(Array.isArray(saved?.servers))prefs.servers=saved.servers.filter(s=>s&&typeof s.name==='string'&&typeof s.url==='string');
  } catch { storageAvailable=false; }
  if (!providers.some(([id])=>id===prefs.provider)) prefs.provider='none';
  onProviderChange(composerLabels[prefs.provider]);
  let route='overview';
  let disposeConnection=()=>{};
  let body,status;
  function persist() {
    try { localStorage.setItem('pocket-preview-settings',JSON.stringify(prefs));status&&(status.textContent='Saved on this browser'); }
    catch { status&&(status.textContent='Could not save locally. Changes apply to this session.'); }
  }
  function heading(title,description) {body.append(el('h2','settings-section-title',title));if(description)body.append(el('p','settings-description',description));}
  function group() {const node=el('div','settings-group');body.append(node);return node;}
  function row(parent,{label,detail,glyph,action,disabled=false}) {
    const node=el(action?'button':'div','settings-row');
    if(action){node.type='button';node.onclick=action;node.disabled=disabled;}
    if(glyph)node.append(icon(glyph));
    const copy=el('span','settings-row-copy');copy.append(el('span','settings-row-label',label));if(detail)copy.append(el('small','settings-row-detail',detail));node.append(copy);
    if(action)node.append(icon('arrow'));parent.append(node);return node;
  }
  function field(parent,key,label,{type='text',placeholder='',multiline=false}={}) {
    const wrap=el('div','settings-field');const labelNode=el('label','',label);labelNode.htmlFor=`pref-${key}`;
    const input=el(multiline?'textarea':'input');input.id=`pref-${key}`;if(!multiline)input.type=type;else input.rows=5;
    input.value=prefs[key];input.placeholder=placeholder;input.maxLength=multiline?4000:120;
    input.oninput=()=>{prefs[key]=input.value;persist();};wrap.append(labelNode,input);parent.append(wrap);return input;
  }
  function select(parent,key,label,options,value,onChange) {
    const wrap=el('div','settings-field');const labelNode=el('label','',label);labelNode.htmlFor=`pref-${key}`;const input=el('select');input.id=`pref-${key}`;
    for(const [id,text] of options){const option=el('option','',text);option.value=id;input.append(option);}input.value=value;
    input.onchange=()=>onChange(input.value);wrap.append(labelNode,input);parent.append(wrap);return input;
  }
  function toggle(parent,key,label,description) {
    const wrap=el('label','settings-switch-row');const text=el('span');text.append(el('span','settings-row-label',label),el('small','settings-row-detail',description));
    const input=el('input');input.type='checkbox';input.checked=prefs[key];input.id=`pref-${key}`;input.onchange=()=>{prefs[key]=input.checked;persist();};wrap.append(text,input);parent.append(wrap);
  }
  function nativeNote(text) {body.append(el('p','settings-native-note',text));}
  function render(next=route) {
    disposeConnection();disposeConnection=()=>{};
    route=next;screen.replaceChildren();
    const titles={overview:'Settings',profile:'Your profile',ai:'AI & model',instructions:'Personal instructions',voice:'Voice & wake word',phone:'Phone access',connections:'Connections',background:'Background work'};
    const header=el('header','settings-header');const back=el('button','icon-button');back.type='button';back.id='settings-back';back.setAttribute('aria-label',route==='overview'?'Back to chat':'Back to settings');back.append(icon('back'));back.onclick=()=>route==='overview'?close():render('overview');
    const title=el('h1','settings-title',titles[route]);title.tabIndex=-1;header.append(back,title);
    body=el('div','settings-body');status=el('p','settings-save-status',storageAvailable?'Preferences stay on this browser':'Local storage unavailable');status.setAttribute('role','status');
    screen.append(header,body);
    if(route==='overview') {
      const profile=el('button','settings-profile');profile.type='button';profile.onclick=()=>render('profile');
      const avatar=el('span','profile-avatar',prefs.name.trim()?prefs.name.trim()[0].toUpperCase():'P');const copy=el('span');copy.append(el('strong','',prefs.name.trim()||'Your Pocket'),el('small','','Personal agent · Local profile'));profile.append(avatar,copy,icon('arrow'));body.append(profile);
      heading('Your assistant');const assistant=group();
      row(assistant,{label:'AI & model',detail:providers.find(p=>p[0]===prefs.provider)?.[1]||'Not configured',glyph:'model',action:()=>render('ai')});
      row(assistant,{label:'Personal instructions',detail:prefs.instructions?'Your preferences are saved':'How Pocket should work with you',glyph:'edit',action:()=>render('instructions')});
      row(assistant,{label:'Voice & wake word',detail:({android:'Android / system speech',groq:'Groq Whisper',deepgram:'Deepgram',hybrid:'Hybrid · Android app'})[prefs.stt]||'Speech recognition',glyph:'voice',action:()=>render('voice')});
      heading('On your phone');const phone=group();
      row(phone,{label:'Phone access',detail:'Permissions & accessibility',glyph:'phone',action:()=>render('phone')});
      row(phone,{label:'Connections',detail:prefs.servers.length?`${prefs.servers.length} connection drafts`:'Remote MCP servers',glyph:'link',action:()=>render('connections')});
      row(phone,{label:'Background work',detail:'Scheduled & autonomous tasks',glyph:'activity',action:()=>render('background')});
      heading('Make it yours');const appearance=group();
      select(appearance,'theme','Appearance',[['dark','Warm charcoal'],['light','Soft paper']],getTheme(),value=>{setTheme(value);status.textContent='Appearance updated';});
      body.append(el('p','settings-about','Pocket Agent · Android companion'));
    } else if(route==='profile') {
      heading('A little more you.','Choose what Pocket should call you.');field(body,'name','Your name',{placeholder:'What should we call you?'});nativeNote('This local profile is separate from provider sign-in.');
    } else if(route==='ai') {
      heading('The brain behind Pocket.','Choose a provider, model, and reasoning preference.');
      select(body,'provider','Provider',providers,prefs.provider,value=>{prefs.provider=value;prefs.model=({'codex-account':'gpt-6-luna',nebius:'zai-org/GLM-5.3-Flash',opencode:'big-pickle'})[value]||'';prefs.reasoning='low';persist();onProviderChange(composerLabels[value]);render('ai');});
      const modelField=field(body,'model','Model ID',{placeholder:'Enter your provider’s model ID'});
      const reasoning=select(body,'reasoning','Reasoning effort',[['minimal','Minimal'],['low','Low'],['medium','Medium'],['high','High'],['xhigh','Extra high'],['max','Maximum']],prefs.reasoning,value=>{prefs.reasoning=value;persist();});
      reasoning.disabled=prefs.provider!=='codex-account';
      if(prefs.provider!=='none')disposeConnection=mountConnection(body,{provider:prefs.provider,getConfig:()=>({provider:prefs.provider,model:prefs.model,reasoning:prefs.reasoning}),onModel:chosen=>{prefs.model=chosen.id;modelField.value=chosen.id;const levels=chosen.supportedReasoningLevels;if(levels?.length&&!levels.includes(prefs.reasoning)){prefs.reasoning=chosen.defaultReasoningLevel||levels[0];reasoning.value=prefs.reasoning;}persist();}});
      else nativeNote('Choose a provider to sign in or add its key.');
    } else if(route==='instructions') {
      heading('Help Pocket understand you.','Your tone, priorities, and preferences.');field(body,'instructions','Personal instructions',{multiline:true,placeholder:'Keep replies concise. Ask before making plans. Call me…'});
      nativeNote('Used in this local chat. The Android app keeps its own personal instructions.');
    } else if(route==='voice') {
      heading('A conversation, out loud.','Choose how Pocket hears you.');
      select(body,'stt','Speech recognition',[['android','Android / system default'],['groq','Groq Whisper'],['deepgram','Deepgram'],['hybrid','Hybrid · Android app']],prefs.stt,value=>{prefs.stt=value;persist();render('voice');});
      const voice=group();toggle(voice,'wake','Hey Jarvis','Always-listening wake word · Android app only.');voice.querySelector('input').disabled=true;
      if(prefs.stt==='groq')disposeConnection=mountVoiceConnection(body,'groq');
      else if(prefs.stt==='deepgram'||prefs.stt==='hybrid')disposeConnection=mountVoiceConnection(body,'deepgram');
      else nativeNote('System speech needs no Deepgram or Groq key. Browser support depends on the installed speech recognizer.');
      nativeNote('The microphone fills the message box so you can review speech before sending, even without an AI provider. Hey Jarvis and hybrid live recognition remain Android features.');
    } else if(route==='phone') {
      heading('You choose what Pocket can use.','Phone access is managed by Android. Nothing is granted from this preview.');const permissions=group();
      for(const label of ['Microphone','Notifications','Contacts','Phone calls','SMS','Calendar'])row(permissions,{label,detail:'Not connected · manage in Android'});
      const tools=group();row(tools,{label:'Accessibility service',detail:'Observe and control the visible screen'});row(tools,{label:'Shizuku virtual displays',detail:'Background display tools · Android only'});
    } else if(route==='connections') {
      heading('A few more possibilities.','Draft a remote MCP connection. This preview will not contact the server.');
      const list=group();if(!prefs.servers.length)row(list,{label:'No connections yet',detail:'Add a name and HTTPS endpoint below.'});
      for(const server of prefs.servers)row(list,{label:server.name,detail:server.url});
      const form=el('form','connection-form');const nameLabel=el('label','','Server name');nameLabel.htmlFor='server-name';const name=el('input');name.id='server-name';name.required=true;name.maxLength=80;name.placeholder='My tools';
      const urlLabel=el('label','','HTTPS endpoint');urlLabel.htmlFor='server-url';const url=el('input');url.id='server-url';url.type='url';url.required=true;url.placeholder='https://example.com/mcp';
      const add=el('button','settings-primary','Save connection draft');add.type='submit';
      url.oninput=()=>url.setCustomValidity('');name.oninput=()=>name.setCustomValidity('');
      form.onsubmit=event=>{event.preventDefault();if(!name.value.trim()){name.setCustomValidity('Enter a server name.');name.reportValidity();return;}if(new URL(url.value).protocol!=='https:'){url.setCustomValidity('Use an HTTPS endpoint.');url.reportValidity();return;}if(prefs.servers.some(s=>s.url===url.value.trim())){url.setCustomValidity('This endpoint is already in your drafts.');url.reportValidity();return;}prefs.servers.push({name:name.value.trim(),url:url.value.trim()});persist();render('connections');};
      form.append(nameLabel,name,urlLabel,url,add);body.append(form);nativeNote('Authentication, headers, and connection testing are configured in Android.');
    } else if(route==='background') {
      heading('Let Pocket do the legwork.','Keep talking while longer tasks run in the background.');const work=group();toggle(work,'autonomy','Scheduled & autonomous work','Preview preference · does not start any tasks.');row(work,{label:'Active background tasks',detail:'None in this preview'});nativeNote('Enable and manage real schedules in the Android app.');
    }
    body.append(status,el('p','settings-preview-note','Local companion · Credentials stay on this computer. Phone tools require Android.'));
    body.scrollTop=0;title.focus({preventScroll:true});
  }
  function close() {disposeConnection();screen.hidden=true;onClose();}
  screen.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();route==='overview'?close():render('overview');}});
  return {setModel(config){prefs.model=config.model;prefs.reasoning=config.reasoning;persist();},open(section='overview'){screen.hidden=false;render(section);},preferences(){return {provider:prefs.provider,model:prefs.model,reasoning:prefs.reasoning,instructions:prefs.instructions,stt:prefs.stt};}};
}
