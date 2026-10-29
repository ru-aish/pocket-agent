import {mountModelPicker} from './model-picker.js';
import { createSettings } from './settings.js';
import { api } from './connection.js';
import { createVoice } from './voice.js';
import { createActivity } from './activity.js';
import { attachDrawerSwipe } from './drawer-swipe.js';
import {shortTitle} from './chat-titles.js';
import {reconcileMessages,mountComposer,mountThinkingIndicator} from './chat-view.js';

const icons = {
  menu:'M4 7h16M4 12h12M4 17h16',
  chevron:'m8 10 4 4 4-4',
  stop:'M6 6h12v12H6V6Z',
  plus:'M12 5v14M5 12h14', search:'m21 21-4.5-4.5M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  settings:'M9 4h6l1 3 3 1v6l-3 1-1 3H9l-1-3-3-1V8l3-1 1-3ZM15 11a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  device:'M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM10 17h4',
  activity:'M3 12h4l3-8 4 16 3-8h4', sun:'M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  layers:'m3 8 9-5 9 5-9 5-9-5ZM3 12l9 5 9-5M3 16l9 5 9-5', edit:'m15 4 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14v6ZM13 20h8',
  chat:'M20 16a2 2 0 0 1-2 2H9l-5 3V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10ZM8 9h8M8 13h5',
  arrow:'M5 12h14m-5-5 5 5-5 5', up:'M12 19V5m-6 6 6-6 6 6', close:'m6 6 12 12M6 18 18 6',
  pocket:'M5 4h14v11c0 3-7 6-7 6s-7-3-7-6V4Zm4 6 3 3 3-3', copy:'M9 9h11v12H9V9ZM5 15H3V3h12v2', mic:'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0V5ZM5 11v1a7 7 0 0 0 14 0v-1M12 19v3M8 22h8'
};
function paintIcons(root = document) {
  for (const node of root.querySelectorAll('[data-icon]')) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', icons[node.dataset.icon] || icons.pocket); svg.append(path); node.replaceChildren(svg);
  }
}
const input = document.querySelector('#message');
mountComposer(input);
icons.wave='M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4';
const send = document.querySelector('#send');
const panel = document.querySelector('#panel');
const panelBody = document.querySelector('#panel-body');
const notice = document.querySelector('#notice');
const drawer = document.querySelector('#chat-drawer');
const drawerTrigger = document.querySelector('#open-chats');
const chatSearch = document.querySelector('#chat-search');
const settings = createSettings({
  screen:document.querySelector('#settings-screen'),
  onClose:() => { mainScreen.inert=false; drawerTrigger.focus({preventScroll:true}); },
  getTheme:() => theme,
  setTheme:value => { theme=value; document.documentElement.dataset.theme=value; save('pocket-preview-theme',value); },
  onProviderChange:label => {
    document.querySelector('#provider-label').textContent=label;
    document.querySelector('#provider-picker').setAttribute('aria-label',`${label} · Choose AI provider`);
  }
});
const drawerLayer = document.querySelector('#drawer-layer');
const mainScreen = document.querySelector('.main');
const activity=createActivity({screen:document.querySelector('#activity-screen'),onClose:()=>{mainScreen.inert=false;drawerTrigger.focus({preventScroll:true});}});
const phoneFrame = document.querySelector('.workspace');
const closeChats = document.querySelector('#close-chats');
const voice=createVoice({button:document.querySelector('#voice-button'),feedback:document.querySelector('#voice-feedback'),status:document.querySelector('#voice-status'),audio:document.querySelector('#voice-playback'),getConfig:()=>settings.preferences(),openSettings:section=>{mainScreen.inert=true;settings.open(section);},cancelResponse:()=>chatController?.abort(),paint:paintIcons,onTranscript:text=>{input.value=text;syncInput();input.focus();notice.textContent='Voice text added. Review it, then send when ready.';}});
document.querySelector('#provider-picker').onclick=()=>{mainScreen.inert=true;settings.open('ai');};
let drawerCloseTimer;
function closeDrawer() {
  if (!drawerLayer.classList.contains('is-open')) return;
  drawerLayer.classList.remove('is-open');
  phoneFrame.classList.remove('chats-open');
  drawer.inert = true;
  mainScreen.inert = false;
  drawerTrigger.setAttribute('aria-expanded','false');
  drawerTrigger.focus({preventScroll:true});
  const duration = matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 220;
  drawerCloseTimer = setTimeout(() => drawer.close(), duration);
}
function openDrawer() {
  clearTimeout(drawerCloseTimer);
  renderHistory();
  drawer.inert = false;
  if (!drawer.open) drawer.show();
  drawer.getBoundingClientRect();
  drawerLayer.classList.add('is-open');
  phoneFrame.classList.add('chats-open');
  mainScreen.inert = true;
  drawerTrigger.setAttribute('aria-expanded','true');
  closeChats.focus({preventScroll:true});
}
drawerTrigger.onclick = openDrawer;
attachDrawerSwipe({workspace:phoneFrame,isOpen:()=>drawerLayer.classList.contains('is-open'),canOpen:()=>document.querySelector('#settings-screen').hidden&&document.querySelector('#activity-screen').hidden&&!panel.open,open:openDrawer,close:closeDrawer});
closeChats.onclick = closeDrawer;
document.querySelector('#drawer-backdrop').onclick = closeDrawer;
document.addEventListener('keydown', event => {
  if (!drawerLayer.classList.contains('is-open')) return;
  if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); }
  if (event.key !== 'Tab') return;
  const controls = [...drawer.querySelectorAll('button:not(:disabled),input,a[href]')];
  const first = controls[0], last = controls.at(-1);
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
});
chatSearch.addEventListener('input', () => renderHistory());
let conversations = [];
let current = { id: crypto.randomUUID(), messages: [] };
let theme = 'dark';
const setThinking=mountThinkingIndicator(document.querySelector('#conversation'));
let responding=false,chatController;
const modelPicker=mountModelPicker({getConfig:()=>settings.preferences(),isBusy:()=>responding,save:async config=>settings.setModel(config),onConnect:()=>openPanel('settings')});
document.querySelector('#provider-picker').onclick=()=>void modelPicker.open();
try {
  const saved = JSON.parse(localStorage.getItem('pocket-preview-chats') || '[]');
  if (Array.isArray(saved)) conversations = saved.filter(chat => chat && typeof chat.id === 'string' && Array.isArray(chat.messages)).map(chat => ({id:chat.id,title:typeof chat.title==='string'?shortTitle(chat.title):'',messages:chat.messages.filter(m => m && ['user','assistant'].includes(m.role) && typeof m.text === 'string')}));
  const savedTheme = localStorage.getItem('pocket-preview-theme');
  if (savedTheme === 'light' || savedTheme === 'dark') theme = savedTheme;
  input.value = localStorage.getItem('pocket-preview-draft') || '';
} catch { notice.textContent = 'Local storage is unavailable. This preview will work for this session.'; }
document.documentElement.dataset.theme = theme;
function save(key, value) { try { localStorage.setItem(key, value); } catch { notice.textContent = 'Could not save locally. Keep this tab open to retain your preview.'; } }
function title(chat) { return shortTitle(chat.title||chat.messages.find(message => message.role === 'user')?.text); }
const naming=new Set();
async function nameChat(chat){
  if(chat.title||naming.has(chat.id)||responding||!chat.messages.some(m=>m.role==='assistant'))return;
  const config=settings.preferences();if(config.provider==='none')return;naming.add(chat.id);
  let renamed=false;
  try{const result=await api('/api/chat/title',{...config,text:chat.messages.slice(0,2).map(m=>`${m.role}: ${m.text}`).join('\n').slice(0,4000)});chat.title=shortTitle(result.title);save('pocket-preview-chats',JSON.stringify(conversations));renamed=true;}catch{}finally{naming.delete(chat.id);}
  if(renamed)renderHistory();
}
function syncInput() { const live=!responding&&!input.value.trim();send.disabled=false;send.classList.toggle('is-voice',live);send.setAttribute('aria-label',responding?'Stop response':live?'Start Codex live voice':'Send message');send.querySelector('[data-icon]').dataset.icon=responding?'stop':live?'wave':'up';paintIcons(send);save('pocket-preview-draft', input.value); }
function setResponding(value){responding=value;setThinking(value);send.setAttribute('aria-label',value?'Stop response':'Send message');send.querySelector('[data-icon]').dataset.icon=value?'stop':'up';paintIcons(send);syncInput();}
function renderHistory(target = document.querySelector('#history'), query = chatSearch.value) {
  if (!target) return;
  target.replaceChildren();
  const matches = conversations.filter(chat => chat.messages.some(m => m.text.toLowerCase().includes(query.toLowerCase())));
  if(!responding){const unnamed=matches.find(chat=>!chat.title&&chat.messages.some(m=>m.role==='assistant')&&!naming.has(chat.id));if(unnamed&&naming.size===0)void nameChat(unnamed);}
  for (const chat of matches) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'history-item'; button.textContent = title(chat);
    if (chat.id === current.id) button.setAttribute('aria-current','page');
    button.onclick = () => { current = chat; render(); closeDrawer(); if (panel.open) panel.close(); };
    target.append(button);
  }
  if (!matches.length) { const text = document.createElement('p'); text.className = 'history-empty'; text.textContent = query ? 'No chats match your search.' : 'No chats yet. Start a conversation and it will appear here.'; target.append(text); }
}
function render() {
  const hasMessages = current.messages.length > 0;
  document.querySelector('#show-threads').hidden=!hasMessages;
  document.querySelector('#welcome').hidden = hasMessages;
  const list = document.querySelector('#messages');const scroller=document.querySelector('#conversation'),top=scroller.scrollTop,follow=scroller.scrollHeight-top-scroller.clientHeight<100;list.hidden = !hasMessages;
  document.querySelector('#copy-chat').disabled = !hasMessages;
  reconcileMessages(list,current.messages);
  current.messages.forEach((message,index)=>{const article=list.children[index];if(message.role!=='assistant'){article.querySelector('.read-reply')?.remove();return;}let read=article.querySelector('.read-reply');if(!read){read=document.createElement('button');read.type='button';read.className='read-reply';read.textContent='Read aloud';article.append(read);}read.onclick=()=>voice.speak(message.text);});
  renderHistory();
  if(follow)scroller.scrollTop=scroller.scrollHeight;else if(scroller.scrollTop!==top)scroller.scrollTop=top;
}
function openPanel(name) {
  if(name==='threads'){mainScreen.inert=true;const screen=document.querySelector('#settings-screen');screen.hidden=false;screen.replaceChildren();const header=document.createElement('header');header.className='settings-header';const back=document.createElement('button');back.type='button';back.className='icon-button';back.setAttribute('aria-label','Back to chat');back.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 6-6 6 6 6"/></svg>';back.onclick=()=>{screen.hidden=true;mainScreen.inert=false;};const heading=document.createElement('h1');heading.className='settings-title';heading.textContent='Threads';header.append(back,heading);const body=document.createElement('div');body.className='settings-body';const text=document.createElement('p');text.className='settings-description';text.textContent='No subagent threads in this browser session. On your phone, threads from this conversation appear here.';body.append(text);screen.append(header,body);return;}
  if (name === 'settings') { mainScreen.inert=true; settings.open(); return; }
  if (name === 'activity') { mainScreen.inert=true; activity.open(); return; }
  panelBody.replaceChildren();
  document.querySelector('#panel-title').textContent = name === 'settings' ? 'Make yourself at home' : name === 'search' ? 'Your conversations' : 'In the background';
  if (name === 'search') {
    const label = document.createElement('label'); label.className = 'field-label'; label.htmlFor = 'search'; label.textContent = 'Search saved preview conversations';
    const search = document.createElement('input'); search.id = 'search'; search.className = 'search-input'; search.type = 'search'; search.placeholder = 'Find a thought…';
    const results = document.createElement('div'); results.className = 'search-results'; search.oninput = () => renderHistory(results, search.value);
    panelBody.append(label, search, results); renderHistory(results);
  } else {
    const text = document.createElement('p'); text.className = 'dialog-copy'; text.textContent = 'No active tasks in this design preview. In the Android app, background tasks appear here while you continue talking to Pocket.';
    const footer = document.createElement('p'); footer.className = 'dialog-footer'; footer.textContent = 'This page previews the interface. It does not connect to your phone, send messages to a model, or start voice listening.';
    panelBody.append(text, footer);
  }
  panel.showModal();
}
for (const button of document.querySelectorAll('[data-action]')) button.addEventListener('click', () => {
  closeDrawer();
  if (button.dataset.action === 'new') { current = {id:crypto.randomUUID(),messages:[]}; input.value=''; chatSearch.value=''; syncInput(); render(); window.dispatchEvent(new Event('pocket-composer-reset')); }
  else if (button.dataset.action === 'copy') {
    navigator.clipboard.writeText(current.messages.map(m => `${m.role}:\n${m.text}`).join('\n\n')).then(() => { notice.textContent='Conversation copied.'; }, () => { notice.textContent='Clipboard unavailable. Select and copy the conversation text.'; });
  } else openPanel(button.dataset.action);
});
for (const button of document.querySelectorAll('[data-prompt]')) button.addEventListener('click', () => { input.value = button.dataset.prompt; syncInput(); input.focus(); });
document.querySelector('#close-panel').onclick = () => panel.close();
document.querySelector('#show-threads').onclick=()=>openPanel('threads');
input.addEventListener('input', syncInput);
input.addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (!responding&&!send.disabled) document.querySelector('#composer').requestSubmit(); } });
function persistConversation(chat){conversations=[chat,...conversations.filter(item=>item.id!==chat.id)].slice(0,30);save('pocket-preview-chats',JSON.stringify(conversations));}
document.querySelector('#composer').addEventListener('submit', event => {event.preventDefault();void submitMessage();});
async function submitMessage(){
  if(responding){chatController?.abort();return;}
  const text=input.value.trim();if(!text){mainScreen.inert=true;settings.open('voice');notice.textContent='Codex live voice is available in the Android app.';return;}
  const config=settings.preferences();
  if(config.provider==='none'){notice.textContent='Choose a provider and connect it to start chatting.';mainScreen.inert=true;settings.open('ai');return;}
  const target=current;target.messages.push({role:'user',text});persistConversation(target);
  input.value='';chatController=new AbortController();setResponding(true);render();notice.textContent='Waiting for your provider…';
  try{
    const result=await api('/api/chat',{...config,messages:target.messages.map(({role,text})=>({role,text}))},{signal:chatController.signal});
    target.messages.push({role:'assistant',text:result.text,provider:result.provider});persistConversation(target);if(current===target){render();notice.textContent='';}return result.text;
  }catch(error){notice.textContent=error.name==='AbortError'?'Response cancelled.':error.message;}
  finally{setResponding(false);input.focus();void nameChat(target);}
}
paintIcons(); syncInput(); renderHistory();
const updates = new EventSource('/events'); updates.onmessage = event => { if (event.data === 'reload') location.reload(); };
