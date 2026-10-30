import test from 'node:test';
import assert from 'node:assert/strict';
import {parseHTML} from '../../runtime/node_modules/linkedom/esm/index.js';

test('cancelled recorder callbacks cannot contaminate or stop the next recording',async t=>{
 const {window,document}=parseHTML('<button id="mic"><span data-icon="mic"></span></button><section><button>Cancel</button></section><p></p><audio></audio>');
 globalThis.window=window;globalThis.document=document;window.PocketNative={};window.pocketRequest=async()=>({voice:{groq:true}});
 const previousNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){}}]})}}});
 t.after(()=>Object.defineProperty(globalThis,'navigator',previousNavigator));
 const recorders=[],timeouts=[];
 class Recorder {constructor(){recorders.push(this);this.state='inactive';this.mimeType='audio/webm';}static isTypeSupported(){return true;}start(){this.state='recording';}stop(){this.state='inactive';}}
 window.MediaRecorder=globalThis.MediaRecorder=Recorder;t.after(()=>delete globalThis.MediaRecorder);
 const originalTimer=globalThis.setTimeout;t.mock.method(globalThis,'setTimeout',(fn,ms,...rest)=>ms===30000?(timeouts.push(fn),999):originalTimer(fn,ms,...rest));
 let uploaded,transcript;
 t.mock.method(globalThis,'fetch',async(_path,init)=>{uploaded=await init.body.text();return Response.json({text:'New speech'});});
 const {createVoice}=await import('../voice.js');
 const button=document.querySelector('#mic'),feedback=document.querySelector('section'),status=document.querySelector('p'),audio=document.querySelector('audio');audio.pause=()=>{};
 const voice=createVoice({button,feedback,status,audio,onTranscript:text=>transcript=text,getConfig:()=>({stt:'groq'}),openSettings(){},cancelResponse(){},paint(){}});
 await button.onclick();const first=recorders[0];voice.cancel();await button.onclick();const second=recorders[1];
 first.ondataavailable({data:new Blob(['old'])});first.onerror();timeouts[0]();await first.onstop();
 assert.equal(second.state,'recording');assert.equal(button.getAttribute('aria-pressed'),'true');
 second.ondataavailable({data:new Blob(['new'])});second.stop();await second.onstop();
 assert.equal(uploaded,'new');assert.equal(transcript,'New speech');assert.equal(button.getAttribute('aria-pressed'),'false');voice.cancel();
});
